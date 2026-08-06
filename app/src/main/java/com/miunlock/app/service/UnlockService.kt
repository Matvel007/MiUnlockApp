package com.miunlock.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miunlock.app.MainActivity
import com.miunlock.app.MiUnlockApp
import com.miunlock.app.R
import com.miunlock.app.domain.ApplyResult
import com.miunlock.app.domain.RunPhase
import com.miunlock.app.domain.ServiceSnapshot
import com.miunlock.app.data.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class UnlockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container get() = (application as MiUnlockApp).container
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrationEnabled: Boolean = true
    private var statusNotificationsEnabled: Boolean = true
    private var language: String = "ru"
    private var resultShown = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopWork("Остановлено пользователем")
                START_NOT_STICKY
            }
            ACTION_CHECK -> {
                if (runJob?.isActive != true) startWork(checkOnly = true)
                // A manual check must never restart later as a scheduled submission.
                START_NOT_STICKY
            }
            else -> {
                if (runJob?.isActive != true) startWork()
                START_STICKY
            }
        }
    }

    private fun startWork(checkOnly: Boolean = false) {
        resultShown = false
        startForeground(STATUS_NOTIFICATION_ID, statusNotification("Подготовка…"))
        runJob = scope.launch {
            try {
                if (!checkOnly) container.settingsStore.markRunning(true)
                acquireWakeLock()
                runCycle(checkOnly)
            } catch (_: CancellationException) {
                DebugLog.add("Фоновая задача отменена")
            } catch (t: Throwable) {
                val msg = "Ошибка: ${t.message ?: "неизвестная"}"
                DebugLog.add("Ошибка фоновой задачи: ${t::class.simpleName ?: "неизвестная"}")
                publish(RunPhase.ERROR, msg)
                showResult(false, msg)
            } finally {
                if (!resultShown) notifications.cancel(STATUS_INDICATOR_NOTIFICATION_ID)
                releaseWakeLock()
                if (!checkOnly) withContext(NonCancellable) {
                    container.settingsStore.markRunning(false)
                }
                runJob = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun runCycle(checkOnly: Boolean) {
        val settings = container.settingsStore.current()
        vibrationEnabled = settings.vibration
        statusNotificationsEnabled = settings.statusNotifications
        language = settings.language
        require(settings.credentials.isValid) { "Укажите serviceToken и deviceId" }

        publish(RunPhase.CHECKING, "Проверяю состояние аккаунта…")
        val state = retryNetwork { container.api.checkState(settings.credentials, settings.proxy) }
        val statusMessage = when (state.code) {
            1 -> "🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ выдан до ${state.deadline}"
            2 -> "📋 Разрешения НЕТ (можно подать заявку)"
            3 -> "⛔ Разрешения НЕТ. Ошибка аккаунта (повторите после ${state.deadline})"
            4 -> "⛔ Разрешения НЕТ. Аккаунт должен быть зарегистрирован более 30 дней"
            else -> state.message
        }
        if (checkOnly) {
            val isGranted = state.code == 1
            publish(if (isGranted) RunPhase.SUCCESS else RunPhase.IDLE, statusMessage)
            showResult(isGranted, statusMessage)
            return
        }
        if (state.code == 1) {
            publish(RunPhase.SUCCESS, statusMessage)
            showResult(true, statusMessage)
            return
        }
        if (state.code != 2) {
            publish(RunPhase.ERROR, statusMessage)
            showResult(false, statusMessage)
            return
        }

        val syncedNow = container.timeSync.synchronizedNow()
        val monotonicStart = android.os.SystemClock.elapsedRealtimeNanos()
        val beijing = ZoneId.of("Asia/Shanghai")
        val localNow = ZonedDateTime.ofInstant(syncedNow, beijing)
        val nextMidnight = localNow.toLocalDate().plusDays(1).atStartOfDay(beijing).toInstant()
        val target = nextMidnight.minusMillis(settings.delayMs.toLong())
        publish(RunPhase.WAITING, "Ожидаю окно подачи", target)

        var warmed = false
        while (scope.isActive) {
            val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - monotonicStart) / 1_000_000_000.0
            val now = syncedNow.plusNanos((elapsed * 1_000_000_000).toLong())
            val remainingMs = Duration.between(now, target).toMillis()
            if (remainingMs <= 0) break
            if (!warmed && remainingMs <= 10_000) {
                warmed = true
                publish(RunPhase.WARMING, "Прогреваю соединение…", target)
                scope.launch { container.api.warmUp(settings.credentials, settings.proxy) }
            }
            val label = formatCountdown(remainingMs)
            updateForeground("До отправки: $label")
            delay(
                when {
                    remainingMs > 60_000 -> 10_000
                    remainingMs > 5_000 -> 1_000
                    remainingMs > 1_000 -> 50
                    else -> 1
                }
            )
        }

        publish(RunPhase.SENDING, "Отправляю заявку…", target)
        var result = retryNetwork { container.api.apply(settings.credentials, settings.proxy) }
        if (!result.successful) {
            DebugLog.add("Первичный ответ Xiaomi: ${result.message}. Проверяю фактический статус аккаунта...")
            publish(RunPhase.CHECKING, "Проверяю фактический статус аккаунта…", target)
            delay(1500)
            runCatching {
                val state = retryNetwork { container.api.checkState(settings.credentials, settings.proxy) }
                if (state.code == 1) {
                    DebugLog.add("Контрольная проверка: доступ поднят! (${state.message})")
                    result = ApplyResult(
                        code = 1,
                        message = "Доступ успешно получен! (Xiaomi вернул ответ о лимите, но доступ активирован)",
                        serverTime = result.serverTime,
                        successful = true,
                    )
                } else {
                    DebugLog.add("Контрольная проверка: доступ не поднят (${state.message})")
                }
            }
        }
        val phase = if (result.successful) RunPhase.SUCCESS else RunPhase.ERROR
        publish(phase, result.message, target, result.serverTime)
        showResult(result.successful, result.message, result)
    }

    private suspend fun <T> retryNetwork(block: suspend () -> T): T {
        var attempt = 0
        var last: Throwable? = null
        while (attempt < 6) {
            attempt++
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                publish(RunPhase.CHECKING, "Сетевая попытка $attempt/6…", attempt = attempt)
                delay((attempt * 1_000L).coerceAtMost(5_000L))
            }
        }
        throw last ?: IllegalStateException("Сетевая ошибка")
    }

    private fun publish(
        phase: RunPhase,
        message: String,
        target: Instant? = snapshot.value.target,
        serverTime: Instant? = snapshot.value.lastServerTime,
        attempt: Int = snapshot.value.attempt,
    ) {
        _snapshot.value = ServiceSnapshot(phase, message, target, serverTime, Instant.now(), attempt)
        val logMessage = if (phase == RunPhase.ERROR) "ошибка выполнения" else message
        DebugLog.add("Сервис: ${phase.name} - $logMessage")
        updateForeground(message)
    }

    private fun stopWork(message: String) {
        publish(RunPhase.STOPPED, message)
        runJob?.cancel() ?: scope.launch {
            withContext(NonCancellable) { container.settingsStore.markRunning(false) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun statusNotification(text: String) = NotificationCompat.Builder(this, STATUS_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (statusNotificationsEnabled) t("Mi Unlock работает", "Mi Unlock is running") else "Mi Unlock")
        .setContentText(if (statusNotificationsEnabled) notificationMessage(text) else t("Фоновая задача активна", "Background task is active"))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(openAppIntent())
        .apply {
            if (statusNotificationsEnabled) addAction(0, t("Остановить", "Stop"), stopIntent())
        }
        .build()

    private fun updateForeground(text: String) {
        notifications.notify(STATUS_NOTIFICATION_ID, statusNotification(text))
        if (statusNotificationsEnabled) {
            notifications.notify(STATUS_INDICATOR_NOTIFICATION_ID, statusIndicatorNotification(text))
        } else {
            notifications.cancel(STATUS_INDICATOR_NOTIFICATION_ID)
        }
    }

    private fun statusIndicatorNotification(text: String) = NotificationCompat.Builder(this, STATUS_INDICATOR_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(t("MiUnlockApp — ожидание запроса", "MiUnlockApp — waiting to send"))
        .setContentText(notificationMessage(text))
        .setOnlyAlertOnce(true)
        .setSilent(true)
        // MIUI filters ongoing notifications from its shade, so this visible copy must stay regular.
        .setAutoCancel(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openAppIntent())
        .addAction(0, t("Остановить", "Stop"), stopIntent())
        .build()

    private fun showResult(success: Boolean, message: String, result: ApplyResult? = null) {
        resultShown = true
        val server = result?.serverTime?.atZone(ZoneId.of("Asia/Shanghai"))
            ?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val detail = if (server == null) notificationMessage(message) else "${notificationMessage(message)} · ${t("сервер", "server")}: $server GMT+8"
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) t("Готово — заявка принята", "Done — application accepted") else t("Результат заявки", "Application result"))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifications.notify(
            if (statusNotificationsEnabled) STATUS_NOTIFICATION_ID else RESULT_NOTIFICATION_ID,
            notification,
        )
        if (statusNotificationsEnabled) notifications.notify(STATUS_INDICATOR_NOTIFICATION_ID, notification)
        if (vibrationEnabled) {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 180), -1)
            )
        }
    }

    private fun t(ru: String, en: String) = if (language == "en") en else ru

    private fun notificationMessage(message: String): String {
        if (language != "en") return message
        if (message.contains(". Смещение: ")) {
            val (status, measurement) = message.split(". Смещение: ", limit = 2)
            return buildString {
                append(notificationMessage(status))
                append(". Advance: ")
                append(measurement.replace("средний RTT", "average RTT").replace("мс", "ms"))
            }
        }
        return when {
            message.startsWith("🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ выдан до ") ->
                "🎉 PERMISSION GRANTED! Access granted until ${message.removePrefix("🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ выдан до ")}"
            message.startsWith("🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ к разблокировке выдан до ") ->
                "🎉 PERMISSION GRANTED! Unlock access granted until ${message.removePrefix("🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ к разблокировке выдан до ")}"
            message.startsWith("🎉 РАЗРЕШЕНИЕ ЕСТЬ! ") ->
                "🎉 PERMISSION GRANTED! ${notificationMessage(message.removePrefix("🎉 РАЗРЕШЕНИЕ ЕСТЬ! "))}"

            message == "📋 Разрешения НЕТ (можно подать заявку)" ->
                "📋 NO PERMISSION YET (ready to submit application)"
            message.startsWith("📋 Разрешения НЕТ (можно подать заявку)") ->
                "📋 NO PERMISSION YET (ready to submit application)${message.removePrefix("📋 Разрешения НЕТ (можно подать заявку)")}"

            message == "⛔ Разрешения НЕТ. Аккаунт должен быть зарегистрирован более 30 дней" ->
                "⛔ NO PERMISSION. The account must be registered for more than 30 days"
            message.startsWith("⛔ Разрешения НЕТ. Ошибка аккаунта (повторите после ") ->
                "⛔ NO PERMISSION. Account error (retry after ${message.removePrefix("⛔ Разрешения НЕТ. Ошибка аккаунта (повторите после ").removeSuffix(")")})" +
                    if (message.endsWith(")")) ")" else ""
            message.startsWith("⛔ Разрешения НЕТ. ") ->
                "⛔ NO PERMISSION. ${notificationMessage(message.removePrefix("⛔ Разрешения НЕТ. "))}"

            message.startsWith("Доступ к разблокировке уже получен до ") ->
                "Unlock access is already available until ${message.removePrefix("Доступ к разблокировке уже получен до ")}"
            message.startsWith("Ошибка аккаунта. Повторите после ") ->
                "Account error. Retry after ${message.removePrefix("Ошибка аккаунта. Повторите после ")}"
            message.startsWith("Лимит заявок исчерпан. Повторите после ") ->
                "Application limit reached. Retry after ${message.removePrefix("Лимит заявок исчерпан. Повторите после ")}"
            message.startsWith("Ошибка проверки: ") ->
                "Check failed: ${message.removePrefix("Ошибка проверки: ")}"
            message.startsWith("Ошибка прокси: ") ->
                "Proxy error: ${message.removePrefix("Ошибка прокси: ")}"
            message.startsWith("До отправки: ") ->
                "Until sending: ${message.removePrefix("До отправки: ")}"
            message.startsWith("Сетевая попытка ") ->
                "Network attempt ${message.removePrefix("Сетевая попытка ")}"
            message.startsWith("Ошибка: ") ->
                "Error: ${message.removePrefix("Ошибка: ")}"

            message == "Подготовка…" -> "Preparing…"
            message == "Готово к запуску" -> "Ready to start"
            message == "Проверяю состояние аккаунта…" -> "Checking account status…"
            message == "Проверяю фактический статус аккаунта…" -> "Checking actual account status…"
            message == "Ожидаю окно подачи" -> "Waiting for submission window"
            message == "Прогреваю соединение…" -> "Warming up connection…"
            message == "Отправляю заявку…" -> "Submitting application…"
            message == "Остановлено" -> "Stopped"
            message == "Остановлено пользователем" -> "Stopped by user"
            message == "Сетевая ошибка" -> "Network error"
            message == "Данные сохранены локально" -> "Data saved locally"
            message == "Сначала укажите serviceToken и deviceId" -> "Enter serviceToken and deviceId first"
            message == "Сначала укажите данные аккаунта" -> "Enter account details first"
            message == "Можно подать заявку" -> "You can submit an application"
            message == "Аккаунт должен быть зарегистрирован более 30 дней" -> "The account must be registered for more than 30 days"
            message == "Заявка успешно подана" -> "Application submitted successfully"
            message == "Доступ успешно получен! (Xiaomi вернул ответ о лимите, но доступ активирован)" -> "Access granted! (Xiaomi returned quota error, but access is active)"
            message == "Заявка отклонена. Попробуйте позже" -> "Application rejected. Try again later"
            message == "Повторите через минуту" -> "Try again in a minute"
            message == "Повторите позже" -> "Try again later"
            message == "Пустой ответ сервера" -> "Empty server response"
            message == "Неизвестный ответ Xiaomi" -> "Unknown Xiaomi response"
            else -> message
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, UnlockService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiUnlock::Scheduler").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        runJob?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val STATUS_CHANNEL = "unlock_status_v3"
        const val STATUS_INDICATOR_CHANNEL = "unlock_status_indicator_v1"
        const val RESULT_CHANNEL = "unlock_result"
        const val ACTION_START = "com.miunlock.app.START"
        const val ACTION_CHECK = "com.miunlock.app.CHECK"
        const val ACTION_STOP = "com.miunlock.app.STOP"
        private const val STATUS_NOTIFICATION_ID = 6900
        private const val STATUS_INDICATOR_NOTIFICATION_ID = 6902
        private const val RESULT_NOTIFICATION_ID = 6901

        private val _snapshot = MutableStateFlow(ServiceSnapshot())
        val snapshot = _snapshot.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, UnlockService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UnlockService::class.java).setAction(ACTION_STOP))
        }

        fun check(context: Context) {
            val intent = Intent(context, UnlockService::class.java).setAction(ACTION_CHECK)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun formatCountdown(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }
    }
}
