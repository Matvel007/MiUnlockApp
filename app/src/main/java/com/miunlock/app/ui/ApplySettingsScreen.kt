package com.miunlock.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.BuildConfig
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiOrangeSoft
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.miunlock.app.auth.MiLoginActivity
import com.miunlock.app.domain.RunPhase
import com.miunlock.app.data.DebugLog
import com.miunlock.app.ui.theme.MiUnlockTheme
import com.miunlock.app.ui.BackArrow
import com.miunlock.app.ui.LocalLanguage
import com.miunlock.app.ui.RootScreen
import com.miunlock.app.ui.tr
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: AppState,
    dispatch: (AppIntent) -> Unit,
    onBack: () -> Unit,
    onBatterySettings: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PaperBackground()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Настройки", "Settings"), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack) { Text(tr("ГОТОВО", "DONE")) }
                }
            }
            item {
                SketchCard {
                    SectionTitle("01", tr("ЯЗЫК", "LANGUAGE"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { dispatch(AppIntent.SetLanguage("ru")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.settings.language == "ru") MiOrangeSoft else Color.Transparent),
                        ) { Text("🇷🇺", fontSize = 22.sp) }
                        OutlinedButton(
                            onClick = { dispatch(AppIntent.SetLanguage("en")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (state.settings.language == "en") MiOrangeSoft else Color.Transparent),
                        ) { Text("🇬🇧", fontSize = 22.sp) }
                    }
                }
            }
            item { SettingsCard(state, dispatch, onBatterySettings, onNotificationSettings) }
            item { ProxyCard(state, dispatch) }
            item {
                Text(
                    "${tr("Версия", "Version")} ${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    state: AppState,
    dispatch: (AppIntent) -> Unit,
    onBatterySettings: () -> Unit,
    onNotificationSettings: () -> Unit,
) {
    SketchCard {
        SectionTitle("02", tr("НАДЕЖНОСТЬ", "RELIABILITY"))
        ToggleRow(tr("Автовосстановление после перезагрузки", "Resume after reboot"), state.settings.autoResume) { dispatch(AppIntent.SetAutoResume(it)) }
        ToggleRow(tr("Вибрация результата", "Vibrate on result"), state.settings.vibration) { dispatch(AppIntent.SetVibration(it)) }
        ToggleRow(tr("Статус и кнопка остановки в уведомлениях", "Status and stop button in notifications"), state.settings.statusNotifications) { dispatch(AppIntent.SetStatusNotifications(it)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(tr("Отключить экономию батареи", "Disable battery optimization"))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onNotificationSettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(tr("Включить уведомления", "Enable notifications"))
        }
        Text(tr("Для длительного ожидания не отключайте системное уведомление Mi Unlock. На Xiaomi также включите Автозапуск и режим батареи «Без ограничений».", "For long waits, keep the Mi Unlock system notification enabled. On Xiaomi, also enable Autostart and Unrestricted battery mode."), fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ProxyCard(state: AppState, dispatch: (AppIntent) -> Unit) {
    val proxy = state.settings.proxy
    val status = if (proxy.isEnabled) state.proxyStatus ?: tr("Прокси не проверен", "Proxy not checked") else null
    val statusColor = when (status) {
        "Прокси подключён" -> Color(0xFF239B56)
        "Прокси недоступен" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    SketchCard {
        SectionTitle("03", tr("ПРОКСИ ДЛЯ ЗАЯВКИ", "APPLICATION PROXY"))
        Text(
            tr(
                "При включении все запросы Xiaomi для проверки и подачи идут только через этот прокси.",
                "When enabled, all Xiaomi checks and application requests use only this proxy.",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProxyTypeButton(tr("ВЫКЛ", "OFF"), proxy.type == ProxyType.NONE, Modifier.weight(1f)) {
                dispatch(AppIntent.SetProxyType(ProxyType.NONE))
            }
            ProxyTypeButton("HTTP", proxy.type == ProxyType.HTTP, Modifier.weight(1f)) {
                dispatch(AppIntent.SetProxyType(ProxyType.HTTP))
            }
        }
        if (proxy.isEnabled) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = proxy.host,
                onValueChange = { dispatch(AppIntent.SetProxyHost(it)) },
                label = { Text(tr("IP или хост", "IP or host")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = proxy.port.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { dispatch(AppIntent.SetProxyPort(it.filter(Char::isDigit))) },
                label = { Text(tr("Порт", "Port")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = proxy.username,
                onValueChange = { dispatch(AppIntent.SetProxyUsername(it)) },
                label = { Text(tr("Логин", "Username")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = proxy.password,
                onValueChange = { dispatch(AppIntent.SetProxyPassword(it)) },
                label = { Text(tr("Пароль", "Password")) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { dispatch(AppIntent.CheckProxy) },
                enabled = !state.isBusy && proxy.isValid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (state.isBusy) tr("ПРОВЕРЯЮ…", "CHECKING…") else tr("ПРОВЕРИТЬ ПРОКСИ", "CHECK PROXY")) }
        }
        status?.let {
            Text(it, color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ProxyTypeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MiOrangeSoft else Color.Transparent),
        shape = RoundedCornerShape(14.dp),
    ) { Text(label) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DisclaimerCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MiOrangeSoft.copy(alpha = .55f)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Честное использование", fontWeight = FontWeight.Black, color = Color(0xFF8B3A00))
            Text("Используйте приложение только для одного собственного аккаунта. Оно автоматизирует официальный запрос Xiaomi, но не обходит ограничения сервера и не гарантирует выдачу разрешения.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF603014))
        }
    }
}
