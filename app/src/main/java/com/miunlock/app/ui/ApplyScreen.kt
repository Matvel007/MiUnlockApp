package com.miunlock.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.miunlock.app.R
import com.miunlock.app.auth.MiLoginActivity
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState
import com.miunlock.app.domain.RunPhase
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiOrangeSoft
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.data.DebugLog
import com.miunlock.app.ui.theme.MiUnlockTheme
import com.miunlock.app.ui.BackArrow
import com.miunlock.app.ui.LocalLanguage
import com.miunlock.app.ui.RootScreen
import com.miunlock.app.ui.tr
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

@Composable
fun MiUnlockScreen(state: AppState, dispatch: (AppIntent) -> Unit, onHome: () -> Unit = {}) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = true) {
        when {
            showConsole -> showConsole = false
            showSettings -> showSettings = false
            else -> onHome()
        }
    }
    val openNotificationSettings = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) openNotificationSettings()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbar.showSnackbar(localizedMessage(it, state.settings.language))
            dispatch(AppIntent.DismissMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PaperBackground()
            if (showConsole) {
                ConsoleScreen(onBack = { showConsole = false })
                return@Box
            }
            if (showSettings) {
                SettingsScreen(state, dispatch, onBack = { showSettings = false }, onBatterySettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                }, onNotificationSettings = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openNotificationSettings()
                    }
                })
                return@Box
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Header(onHome = onHome, onConsole = { showConsole = true }, onGitHub = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Matvel007/MiUnlockApp")))
                }) }
                item { DualClockCard() }
                item { StatusCard(state) }
                item { SchedulerCard(state, dispatch) }
                item {
                    AccountCard(
                        state = state,
                        onLogin = { context.startActivity(Intent(context, MiLoginActivity::class.java).putExtra("language", state.settings.language)) },
                    )
                }
                item { SettingsEntryCard(onClick = { showSettings = true }) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Header(onHome: () -> Unit = {}, onConsole: () -> Unit, onGitHub: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "github")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "githubScale",
    )
    // Заголовок не пишем: длинное имя не влезает в строку и выдавливает значки.
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onHome) { BackArrow() }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onConsole) {
            ConsoleIcon(MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onGitHub) {
            Icon(painterResource(R.drawable.ic_github), contentDescription = "GitHub", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(29.dp).graphicsLayer { scaleX = scale; scaleY = scale })
        }
    }
}

@Composable
private fun PencilLogo() {
    val transition = rememberInfiniteTransition(label = "pencil")
    val angle by transition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "angle",
    )
    Box(
        Modifier.size(64.dp).rotate(angle).background(MiOrange, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(38.dp)) {
            val ink = Color.White
            drawLine(ink, Offset(size.width * .25f, size.height * .78f), Offset(size.width * .75f, size.height * .28f), 7f, StrokeCap.Round)
            val p = Path().apply {
                moveTo(size.width * .18f, size.height * .86f)
                lineTo(size.width * .28f, size.height * .66f)
                lineTo(size.width * .38f, size.height * .76f)
                close()
            }
            drawPath(p, ink)
            drawLine(Color(0xFFFFD3B5), Offset(size.width * .68f, size.height * .21f), Offset(size.width * .81f, size.height * .34f), 8f, StrokeCap.Round)
        }
    }
}

@Composable
private fun DualClockCard() {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(250)
        }
    }
    val instant = Instant.ofEpochMilli(tick)
    val format = DateTimeFormatter.ofPattern("HH:mm:ss")
    val date = DateTimeFormatter.ofPattern(
        "dd MMM, EEE",
        if (LocalLanguage.current == "en") Locale.US else Locale("ru"),
    )
    val localZone = ZoneId.systemDefault()
    val beijingZone = ZoneId.of("Asia/Shanghai")
    val local = instant.atZone(localZone)
    val beijing = instant.atZone(beijingZone)

    SketchCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ClockBlock(tr("МЕСТНОЕ", "LOCAL"), local.format(format), local.format(date), Modifier.weight(1f))
            DoodleDivider()
            ClockBlock(tr("ПЕКИН", "BEIJING"), beijing.format(format), "GMT+8 · ${beijing.format(date)}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ClockBlock(title: String, time: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = MiOrange, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.4.sp)
        Text(time, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 24.sp)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatusCard(state: AppState) {
    val snapshot = state.snapshot
    val language = LocalLanguage.current
    val running = snapshot.phase in setOf(RunPhase.CHECKING, RunPhase.WAITING, RunPhase.WARMING, RunPhase.SENDING)
    val missingCredentials = !state.settings.credentials.isValid && snapshot.phase == RunPhase.IDLE
    val color = when (snapshot.phase) {
        RunPhase.SUCCESS -> Color(0xFF239B56)
        RunPhase.ERROR -> MaterialTheme.colorScheme.error
        RunPhase.IDLE, RunPhase.STOPPED -> if (missingCredentials) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
        else -> MiOrange
    }
    SketchCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(color.copy(alpha = .14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (running) CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 3.dp, color = color)
                else Text(if (snapshot.phase == RunPhase.SUCCESS) "✓" else "●", color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (missingCredentials) tr("Требуется вход", "Sign in required") else phaseTitle(snapshot.phase),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
                AnimatedContent(
                    if (missingCredentials) tr("Войдите в Mi Account", "Sign in to Mi Account") else localizedMessage(snapshot.message, language),
                    label = "status",
                ) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                snapshot.target?.let {
                    val target = it.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm:ss.SSS"))
                    Text("${tr("Старт", "Start")}: $target", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SchedulerCard(state: AppState, dispatch: (AppIntent) -> Unit) {
    val running = state.snapshot.phase in setOf(RunPhase.CHECKING, RunPhase.WAITING, RunPhase.WARMING, RunPhase.SENDING)
    SketchCard {
        SectionTitle("01", tr("ТОЧНЫЙ ЗАПУСК", "PRECISE START"))
        Text(tr("Заявка уйдёт перед 00:00 по Пекину. NTP-синхронизация компенсирует часы устройства.", "The request is sent before midnight in Beijing. NTP synchronization compensates device clock drift."))
        Spacer(Modifier.height(12.dp))
        Text("${tr("Опережение", "Advance")}: ${state.settings.delayMs} ms", fontWeight = FontWeight.Bold)
        Slider(
            value = state.settings.delayMs.toFloat(),
            onValueChange = { dispatch(AppIntent.SetDelay((it / 50).roundToInt() * 50)) },
            valueRange = 0f..3000f,
            steps = 59,
            enabled = !running,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { dispatch(AppIntent.CheckNow) },
                modifier = Modifier.weight(1f),
                enabled = !state.isBusy && !running,
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (state.isBusy) tr("Проверяю…", "Checking…") else tr("Проверить", "Check")) }
            Button(
                onClick = { dispatch(if (running) AppIntent.Stop else AppIntent.Start) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) MaterialTheme.colorScheme.error else MiOrange),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (running) tr("Остановить", "Stop") else tr("Запустить", "Start")) }
        }
    }
}

@Composable
private fun AccountCard(
    state: AppState,
    onLogin: () -> Unit,
) {
    SketchCard {
        SectionTitle("02", tr("АККАУНТ XIAOMI", "XIAOMI ACCOUNT"))
        Text(
            if (state.settings.credentials.isValid) tr("Сессия Mi Community подключена", "Mi Community session connected")
            else tr("Войдите через Xiaomi Account, чтобы получить сессию Mi Community.", "Sign in to Xiaomi Account to get a Mi Community session."),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.settings.credentials.isValid) Color(0xFF239B56) else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onLogin,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (state.settings.credentials.isValid) tr("СМЕНИТЬ / ОБНОВИТЬ ВХОД", "CHANGE / REFRESH SIGN-IN") else tr("ВОЙТИ В MI ACCOUNT", "SIGN IN TO MI ACCOUNT"))
        }
    }
}

@Composable
private fun SettingsEntryCard(onClick: () -> Unit) {
    SketchCard {
        SectionTitle("03", tr("НАСТРОЙКИ", "SETTINGS"))
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(tr("ОТКРЫТЬ НАСТРОЙКИ", "OPEN SETTINGS"))
        }
    }
}

@Composable
private fun phaseTitle(phase: RunPhase) = when (phase) {
    RunPhase.IDLE -> tr("Готов к работе", "Ready")
    RunPhase.CHECKING -> tr("Проверка", "Checking")
    RunPhase.WAITING -> tr("Ожидание", "Waiting")
    RunPhase.WARMING -> tr("Финальная подготовка", "Final preparation")
    RunPhase.SENDING -> tr("Отправка", "Sending")
    RunPhase.SUCCESS -> tr("Успешно", "Success")
    RunPhase.ERROR -> tr("Нужна проверка", "Needs attention")
    RunPhase.STOPPED -> tr("Остановлено", "Stopped")
}

private fun localizedMessage(message: String, language: String): String {
    if (language != "en") return message
    if (message.contains(". Смещение: ")) {
        val (status, measurement) = message.split(". Смещение: ", limit = 2)
        return buildString {
            append(localizedMessage(status, "en"))
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
            "🎉 PERMISSION GRANTED! ${localizedMessage(message.removePrefix("🎉 РАЗРЕШЕНИЕ ЕСТЬ! "), "en")}"

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
            "⛔ NO PERMISSION. ${localizedMessage(message.removePrefix("⛔ Разрешения НЕТ. "), "en")}"

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
        message == "Отправляю заявку…" -> "Submitting request…"
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
        message == "Ошибка параметров" -> "Invalid parameters"
        message == "Прокси недоступен" -> "Proxy unavailable"
        message == "Прокси подключён" -> "Proxy connected"
        message == "Прокси не проверен" -> "Proxy not verified"
        else -> message
    }
}
