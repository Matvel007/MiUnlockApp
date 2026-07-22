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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private var resultShown = false
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWork("Остановлено пользователем")
            ACTION_CHECK -> if (runJob?.isActive != true) startWork(checkOnly = true)
            else -> if (runJob?.isActive != true) startWork()
        }
        return START_STICKY
    }

    private fun startWork(checkOnly: Boolean = false) {
        resultShown = false
        startForeground(STATUS_NOTIFICATION_ID, statusNotification("Подготовка…"))
        runJob = scope.launch {
            container.settingsStore.markRunning(true)
            acquireWakeLock()
            try {
                runCycle(checkOnly)
            } catch (_: CancellationException) {
                publish(RunPhase.STOPPED, "Остановлено")
            } catch (t: Throwable) {
                val msg = "Ошибка: ${t.message ?: "неизвестная"}"
                publish(RunPhase.ERROR, msg)
                showResult(false, msg)
            } finally {
                if (!resultShown) notifications.cancel(STATUS_INDICATOR_NOTIFICATION_ID)
                releaseWakeLock()
                container.settingsStore.markRunning(false)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun runCycle(checkOnly: Boolean) {
        val settings = container.settingsStore.current()
        vibrationEnabled = settings.vibration
        statusNotificationsEnabled = settings.statusNotifications
        require(settings.credentials.isValid) { "Укажите serviceToken и deviceId" }

        publish(RunPhase.CHECKING, "Проверяю состояние аккаунта…")
        val state = retryNetwork { container.api.checkState(settings.credentials) }
        if (checkOnly) {
            val successful = state.code == 1 || state.code == 2
            publish(if (successful) RunPhase.SUCCESS else RunPhase.ERROR, state.message)
            showResult(successful, state.message)
            return
        }
        if (state.code == 1) {
            publish(RunPhase.SUCCESS, state.message)
            showResult(true, state.message)
            return
        }
        if (state.code != 2) {
            publish(RunPhase.ERROR, state.message)
            showResult(false, state.message)
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
                scope.launch { container.api.warmUp(settings.credentials) }
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
        val result = retryNetwork { container.api.apply(settings.credentials) }
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
        updateForeground(message)
    }

    private fun stopWork(message: String) {
        publish(RunPhase.STOPPED, message)
        runJob?.cancel()
        runJob = null
        scope.launch { container.settingsStore.markRunning(false) }
        releaseWakeLock()
        notifications.cancel(STATUS_INDICATOR_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun statusNotification(text: String) = NotificationCompat.Builder(this, STATUS_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(if (statusNotificationsEnabled) "Mi Unlock работает" else "Mi Unlock")
        .setContentText(if (statusNotificationsEnabled) text else "Фоновая задача активна")
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentIntent(openAppIntent())
        .apply {
            if (statusNotificationsEnabled) addAction(0, "Остановить", stopIntent())
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
        .setContentTitle("MiUnlockApp — ожидание запроса")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        // MIUI filters ongoing notifications from its shade, so this visible copy must stay regular.
        .setAutoCancel(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openAppIntent())
        .addAction(0, "Остановить", stopIntent())
        .build()

    private fun showResult(success: Boolean, message: String, result: ApplyResult? = null) {
        resultShown = true
        val server = result?.serverTime?.atZone(ZoneId.of("Asia/Shanghai"))
            ?.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val detail = if (server == null) message else "$message · сервер: $server GMT+8"
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "Готово — заявка принята" else "Результат заявки")
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
