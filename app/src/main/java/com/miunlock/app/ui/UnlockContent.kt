package com.miunlock.app.ui

import android.hardware.usb.UsbDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.unlock.UnlockApi
import com.miunlock.app.unlock.UnlockSession
import com.miunlock.app.unlock.UnlockStep
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiOrangeSoft


@Composable
internal fun UnlockScreenContent(
    state: UnlockUiState,
    language: String,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onScan: () -> Unit,
    onUnlock: () -> Unit,
    onAnswerConfirm: (Boolean) -> Unit,
    onZone: (String) -> Unit,
    onProxy: (ProxySettings) -> Unit,
    onLanguage: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var showConsole by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showConsole -> showConsole = false
            showSettings -> showSettings = false
            else -> onBack()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showConsole) {
                ConsoleScreen(onBack = { showConsole = false })
                return@Box
            }
            if (showSettings) {
                UnlockSettingsScreen(
                    state = state,
                    language = language,
                    onBack = { showSettings = false },
                    onZone = onZone,
                    onProxy = onProxy,
                    onLanguage = onLanguage,
                    onLogout = { onLogout(); showSettings = false },
                )
                return@Box
            }

            PaperBackground()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    // Без заголовка: он не влезает рядом со значками.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { BackArrow() }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showConsole = true }) {
                            ConsoleIcon(MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showSettings = true }) { GearIcon() }
                    }
                }
                item {
                    AccountStateCard(session = state.session, onLogin = onLogin)
                }
                item {
                    DeviceCard(
                        state = state,
                        onScan = onScan,
                        onUnlock = onUnlock,
                    )
                }
                item { InstructionCard() }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    state.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { onAnswerConfirm(false) },
            title = { Text(tr("Подтвердите разблокировку", "Confirm unlock")) },
            text = {
                Column {
                    Text(
                        if (confirm.cleanOrNot) {
                            tr("Все данные на устройстве будут стёрты. Это необратимо.", "All data on the device will be erased. This cannot be undone.")
                        } else {
                            tr("Данные на устройстве сохранятся.", "Device data will be kept.")
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    if (confirm.notice.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(confirm.notice)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAnswerConfirm(true) }) {
                    Text(tr("РАЗБЛОКИРОВАТЬ", "UNLOCK"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { onAnswerConfirm(false) }) { Text(tr("ОТМЕНА", "CANCEL")) }
            },
        )
    }
}

@Composable
private fun AccountStateCard(session: UnlockSession, onLogin: () -> Unit) {
    SketchCard(container = if (session.isValid) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else null) {
        SectionTitle("01", tr("АККАУНТ", "ACCOUNT"))
        if (session.isValid) {
            Text(tr("Вход выполнен", "Signed in"), fontWeight = FontWeight.Black)
            Text(
                "uid ${session.userId}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                tr("Зона: ${zoneLabel(session.zone)}", "Zone: ${zoneLabel(session.zone)}"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            Text(
                tr(
                    "Для разблокировки нужен вход в Mi Account. Это отдельная сессия — вход из режима заявки здесь не используется.",
                    "Unlock needs its own Mi Account sign-in. This is a separate session — the sign-in from the application mode is not reused.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
            ) { Text(tr("ВОЙТИ В MI ACCOUNT", "SIGN IN TO MI ACCOUNT")) }
        }
    }
}

@Composable
private fun DeviceCard(
    state: UnlockUiState,
    onScan: () -> Unit,
    onUnlock: () -> Unit,
) {
    SketchCard {
        SectionTitle("02", tr("УСТРОЙСТВО", "DEVICE"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MiOrangeSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Метка появляется только вместе с разрешением на USB,
                    // поэтому она же и признак «устройство на связи».
                    if (state.device != null || state.deviceLabel.isNotBlank()) "✓" else "?",
                    color = MiOrange,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.deviceLabel.ifEmpty { tr("Не подключено", "Not connected") },
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.device != null || state.deviceLabel.isNotBlank()) {
                        tr("Готов к разблокировке", "Ready to unlock")
                    } else {
                        tr("Подключите телефон по OTG-кабелю", "Connect the phone with an OTG cable")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        if (state.product.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("codename: ${state.product}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onScan,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text(tr("НАЙТИ УСТРОЙСТВО", "FIND DEVICE")) }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onUnlock,
            enabled = !state.running && state.session.isValid && (state.device != null || state.deviceLabel.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
        ) {
            if (state.running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(state.status.pick(LocalLanguage.current).ifEmpty { tr("РАЗБЛОКИРОВКА…", "UNLOCKING…") })
            } else {
                Text(tr("РАЗБЛОКИРОВАТЬ", "UNLOCK"))
            }
        }
        StatusLine(state)
    }
}

/**
 * Плашка состояния под кнопкой. Цвет и знак зависят от того, что происходит:
 * идёт работа, ждём срок, ошибка или готово. Раньше это была одна серая строка,
 * и срок ожидания выглядел как обычный текст.
 */
@Composable
private fun StatusLine(state: UnlockUiState) {
    val language = LocalLanguage.current
    val text = state.error ?: state.status.pick(language)
    if (text.isBlank()) return

    val kind = when {
        state.step == UnlockStep.DONE -> Kind.DONE
        state.waitHours != null -> Kind.WAIT
        state.error != null -> Kind.FAIL
        state.running -> Kind.WORK
        else -> Kind.INFO
    }
    val accent = when (kind) {
        Kind.DONE -> Color(0xFF6FD08C)
        Kind.WAIT -> MiOrange
        Kind.FAIL -> MaterialTheme.colorScheme.error
        Kind.WORK -> MaterialTheme.colorScheme.primary
        Kind.INFO -> MaterialTheme.colorScheme.secondary
    }
    val sign = when (kind) {
        Kind.DONE -> "✓"
        Kind.WAIT -> "◷"
        Kind.FAIL -> "!"
        Kind.WORK -> "●"
        Kind.INFO -> "•"
    }

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = .14f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(sign, color = accent, fontWeight = FontWeight.Black, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (kind == Kind.WAIT) {
                Text(
                    tr("Осталось примерно ${state.waitHours} ч", "About ${state.waitHours} h left"),
                    color = accent,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(2.dp))
                Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
            } else {
                Text(
                    text,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                if (state.errorCode != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tr("код ответа ${state.errorCode}", "response code ${state.errorCode}"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

private enum class Kind { DONE, WAIT, FAIL, WORK, INFO }

@Composable
private fun InstructionCard() {
    SketchCard {
        SectionTitle("03", tr("ИНСТРУКЦИЯ", "INSTRUCTIONS"))
        val steps = listOf(
            tr("Войдите в Mi Account на этом телефоне.", "Sign in to Mi Account on this phone."),
            tr("Выключите целевой телефон и зажмите Громкость− и Питание — он загрузится в fastboot.", "Power the target phone off and hold Volume Down and Power — it boots into fastboot."),
            tr("Подключите его к этому телефону по OTG-кабелю.", "Connect it to this phone with an OTG cable."),
            tr("Нажмите «Найти устройство» и разрешите доступ к USB.", "Tap “Find device” and allow USB access."),
            tr("Нажмите «Разблокировать» и подтвердите стирание данных.", "Tap “Unlock” and confirm the data wipe."),
        )
        steps.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}.", color = MiOrange, fontWeight = FontWeight.Black, modifier = Modifier.width(22.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr(
                "Право на разблокировку выдаёт сервер Xiaomi по аккаунту. Приложение не обходит ограничения и не гарантирует разрешение.",
                "Unlock permission is granted by Xiaomi for the account. The app does not bypass restrictions and does not guarantee approval.",
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun UnlockSettingsScreen(
    state: UnlockUiState,
    language: String,
    onBack: () -> Unit,
    onZone: (String) -> Unit,
    onProxy: (ProxySettings) -> Unit,
    onLanguage: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var host by remember(state.proxy.host) { mutableStateOf(state.proxy.host) }
    var port by remember(state.proxy.port) { mutableStateOf(state.proxy.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var user by remember(state.proxy.username) { mutableStateOf(state.proxy.username) }
    var pass by remember(state.proxy.password) { mutableStateOf(state.proxy.password) }

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
                            onClick = { onLanguage("ru") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (language == "ru") MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text("RU", fontWeight = FontWeight.Black) }
                        OutlinedButton(
                            onClick = { onLanguage("en") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (language == "en") MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text("EN", fontWeight = FontWeight.Black) }
                    }
                }
            }
            item {
                SketchCard {
                    SectionTitle("02", tr("ЗОНА СЕРВЕРА", "SERVER ZONE"))
                    Text(
                        tr("Сервер выбирается по региону аккаунта. Если ответы с ошибкой — попробуйте другую зону.", "The server follows the account region. If requests fail, try another zone."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    val zones = listOf(
                        UnlockApi.ZONE_SINGAPORE,
                        UnlockApi.ZONE_CHINA,
                        UnlockApi.ZONE_RUSSIA,
                        UnlockApi.ZONE_INDIA,
                        UnlockApi.ZONE_EUROPE,
                    )
                    zones.forEach { z ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            OutlinedButton(
                                onClick = { onZone(z) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (state.session.zone == z) MiOrangeSoft else Color.Transparent,
                                ),
                            ) { Text(zoneLabel(z)) }
                        }
                    }
                }
            }
            item {
                SketchCard {
                    SectionTitle("03", tr("ПРОКСИ ДЛЯ РАЗБЛОКИРОВКИ", "UNLOCK PROXY"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onProxy(ProxySettings(type = ProxyType.NONE)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (state.proxy.type == ProxyType.NONE) MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text(tr("ВЫКЛ", "OFF")) }
                        OutlinedButton(
                            onClick = { onProxy(state.proxy.copy(type = ProxyType.HTTP)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (state.proxy.type == ProxyType.HTTP) MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text("HTTP") }
                    }
                    if (state.proxy.type == ProxyType.HTTP) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(host, { host = it }, label = { Text(tr("IP или хост", "IP or host")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(tr("Порт", "Port")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(user, { user = it }, label = { Text(tr("Логин", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(pass, { pass = it }, label = { Text(tr("Пароль", "Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                onProxy(
                                    ProxySettings(
                                        type = ProxyType.HTTP,
                                        host = host.trim(),
                                        port = port.toIntOrNull() ?: 0,
                                        username = user.trim(),
                                        password = pass,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
                        ) { Text(tr("СОХРАНИТЬ", "SAVE")) }
                    }
                }
            }
            item {
                SketchCard {
                    SectionTitle("04", tr("СЕССИЯ", "SESSION"))
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(tr("ВЫЙТИ ИЗ АККАУНТА", "SIGN OUT")) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun zoneLabel(zone: String): String = when (zone) {
    UnlockApi.ZONE_CHINA -> tr("Китай", "China")
    UnlockApi.ZONE_RUSSIA -> tr("Россия", "Russia")
    UnlockApi.ZONE_INDIA -> tr("Индия", "India")
    UnlockApi.ZONE_EUROPE -> tr("Европа", "Europe")
    else -> tr("Сингапур", "Singapore")
}
