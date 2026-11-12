package com.miunlock.app.ui

import android.app.Application
import android.content.Intent
import android.hardware.usb.UsbDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miunlock.app.data.DebugLog
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.fastboot.FastbootUsb
import com.miunlock.app.unlock.UnlockApi
import com.miunlock.app.unlock.UnlockOutcome
import com.miunlock.app.unlock.UnlockSession
import com.miunlock.app.unlock.UnlockStep
import com.miunlock.app.unlock.UnlockStore
import com.miunlock.app.unlock.UnlockWorkflow
import com.miunlock.app.ui.theme.MiOrange
import com.miunlock.app.ui.theme.MiOrangeSoft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Текст на двух языках: выбор делается при отрисовке, а не при записи. */
data class Bilingual(val ru: String, val en: String) {
    fun pick(language: String): String = if (language == "en") en else ru
    val isEmpty: Boolean get() = ru.isEmpty() && en.isEmpty()
}

/** Состояние экрана разблокировки. */
data class UnlockUiState(
    val session: UnlockSession = UnlockSession(),
    val proxy: ProxySettings = ProxySettings(),
    val deviceLabel: String = "",
    val device: UsbDevice? = null,
    val product: String = "",
    val tokenLength: Int = 0,
    val running: Boolean = false,
    val step: UnlockStep? = null,
    val status: Bilingual = Bilingual("", ""),
    val error: String? = null,
    val confirm: ClearConfirm? = null,
) {
    data class ClearConfirm(val cleanOrNot: Boolean, val notice: String)
}

class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val store = UnlockStore(app)
    private val usb = FastbootUsb(app)

    var state by mutableStateOf(UnlockUiState())
        private set

    /** Язык интерфейса: ViewModel не может читать CompositionLocal. */
    private var lang = "ru"

    fun setLanguage(value: String) {
        lang = value
    }

    private fun t(ru: String, en: String) = if (lang == "en") en else ru

    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch {
            state = state.copy(session = store.session(), proxy = store.proxy())
        }
    }

    fun refreshSession() {
        viewModelScope.launch { state = state.copy(session = store.session()) }
    }

    fun saveSession(session: UnlockSession) {
        viewModelScope.launch {
            store.saveSession(session)
            state = state.copy(session = session)
        }
    }

    fun logout() {
        viewModelScope.launch {
            store.clearSession()
            state = state.copy(session = UnlockSession(), product = "", tokenLength = 0)
            DebugLog.add(t("Разблокировка: сессия очищена", "Unlock: session cleared"))
        }
    }

    fun setZone(zone: String) {
        val next = state.session.copy(zone = zone)
        saveSession(next)
    }

    fun saveProxy(proxy: ProxySettings) {
        viewModelScope.launch {
            store.saveProxy(proxy)
            state = state.copy(proxy = proxy)
        }
    }

    /** Ищет подключённые устройства в fastboot и запрашивает доступ. */
    fun scanDevice() {
        val found = usb.candidates()
        if (found.isEmpty()) {
            state = state.copy(
                device = null,
                deviceLabel = "",
                error = null,
                status = Bilingual("Устройство не найдено", "No device found"),
            )
            DebugLog.add(t("Разблокировка: устройств в fastboot не найдено", "Unlock: no fastboot devices found"))
            return
        }
        val dev = found.first()
        DebugLog.add(t("Разблокировка: найдено ${usb.deviceLabel(dev)}", "Unlock: found ${usb.deviceLabel(dev)}"))
        usb.requestPermission(dev) { granted ->
            state = state.copy(
                device = if (granted) dev else null,
                deviceLabel = if (granted) usb.deviceLabel(dev) else "",
                status = if (granted) Bilingual("Устройство подключено", "Device connected") else Bilingual("Доступ к USB отклонён", "USB access denied"),
                error = if (granted) null else t("Нет доступа к USB-устройству", "No USB permission"),
            )
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    fun answerConfirm(ok: Boolean) {
        state = state.copy(confirm = null)
        confirmDeferred?.complete(ok)
        confirmDeferred = null
    }

    /** Полный цикл: устройство, токен, подпись, stage, unlock. */
    fun startUnlock() {
        val dev = state.device
        if (dev == null) {
            state = state.copy(error = t("Сначала подключите телефон", "Connect a phone first"))
            return
        }
        if (!state.session.isValid) {
            state = state.copy(error = t("Сначала войдите в Mi Account", "Sign in to Mi Account first"))
            return
        }
        if (state.running) return

        state = state.copy(running = true, error = null, status = Bilingual("Подготовка…", "Preparing…"))
        DebugLog.add(t("Разблокировка: старт", "Unlock: started"))

        viewModelScope.launch {
            val workflow = UnlockWorkflow(usb, UnlockApi(state.proxy))
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    workflow.run(
                        session = state.session,
                        device = dev,
                        listener = { p ->
                            viewModelScope.launch {
                                state = state.copy(step = p.step, status = Bilingual(p.ru, p.en))
                                DebugLog.add(t("Разблокировка: ", "Unlock: ") + t(p.ru, p.en))
                            }
                        },
                        onConfirmClear = { clean, notice ->
                            val d = CompletableDeferred<Boolean>()
                            confirmDeferred = d
                            viewModelScope.launch {
                                state = state.copy(confirm = UnlockUiState.ClearConfirm(clean, notice))
                            }
                            d.await()
                        },
                    )
                }.getOrElse { UnlockOutcome.Failure(UnlockStep.DEVICE, it.message ?: "ошибка") }
            }

            state = when (outcome) {
                is UnlockOutcome.Success -> state.copy(
                    running = false,
                    status = Bilingual("Разблокировано", "Unlocked"),
                    step = UnlockStep.DONE,
                )
                is UnlockOutcome.Cancelled -> state.copy(
                    running = false,
                    status = Bilingual("Отменено", "Cancelled"),
                )
                is UnlockOutcome.Failure -> state.copy(
                    running = false,
                    status = Bilingual("Остановлено на шаге ${outcome.step.name}", "Stopped at ${outcome.step.name}"),
                    error = outcome.message,
                )
            }
            DebugLog.add(t("Разблокировка: ", "Unlock: ") + state.status.pick(lang))
        }
    }
}

@Composable
fun UnlockScreen(onBack: () -> Unit, onLanguage: (String) -> Unit = {}) {
    val vm: UnlockViewModel = viewModel()
    val context = LocalContext.current
    var showConsole by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        when {
            showConsole -> showConsole = false
            showSettings -> showSettings = false
            else -> onBack()
        }
    }

    val language = LocalLanguage.current
    LaunchedEffect(language) {
        vm.setLanguage(language)
        vm.refreshSession()
    }

    val s = vm.state

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showConsole) {
                ConsoleScreen(onBack = { showConsole = false })
                return@Box
            }
            if (showSettings) {
                UnlockSettingsScreen(
                    state = s,
                    language = language,
                    onBack = { showSettings = false },
                    onZone = vm::setZone,
                    onProxy = vm::saveProxy,
                    onLanguage = onLanguage,
                    onLogout = { vm.logout(); showSettings = false },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { BackArrow() }
                        Text(tr("Разблокировка", "Unlock"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showConsole = true }) {
                            ConsoleIcon(MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showSettings = true }) { GearIcon() }
                    }
                }
                item {
                    AccountStateCard(
                        session = s.session,
                        onLogin = {
                            context.startActivity(
                                Intent(context, com.miunlock.app.auth.MiLoginActivity::class.java)
                                    .putExtra("mode", "unlock")
                                    .putExtra("language", language),
                            )
                        },
                    )
                }
                item {
                    DeviceCard(
                        state = s,
                        onScan = vm::scanDevice,
                    )
                }
                item {
                    Button(
                        onClick = vm::startUnlock,
                        enabled = !s.running && s.session.isValid && s.device != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MiOrange),
                    ) {
                        if (s.running) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text(s.status.pick(language).ifEmpty { tr("РАЗБЛОКИРОВКА…", "UNLOCKING…") })
                        } else {
                            Text(tr("РАЗБЛОКИРОВАТЬ", "UNLOCK"), fontWeight = FontWeight.Black)
                        }
                    }
                }
                item { StatusLine(s) }
                item { InstructionCard() }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    s.confirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { vm.answerConfirm(false) },
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
                TextButton(onClick = { vm.answerConfirm(true) }) {
                    Text(tr("РАЗБЛОКИРОВАТЬ", "UNLOCK"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.answerConfirm(false) }) { Text(tr("ОТМЕНА", "CANCEL")) }
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
private fun DeviceCard(state: UnlockUiState, onScan: () -> Unit) {
    SketchCard {
        SectionTitle("02", tr("УСТРОЙСТВО", "DEVICE"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MiOrangeSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.device != null) "✓" else "?",
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
                    tr("Подключите телефон по OTG в режиме fastboot", "Connect the phone with an OTG cable in fastboot mode"),
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text(tr("НАЙТИ УСТРОЙСТВО", "FIND DEVICE")) }
    }
}

@Composable
private fun StatusLine(state: UnlockUiState) {
    val language = LocalLanguage.current
    val color = when {
        state.error != null -> MaterialTheme.colorScheme.error
        state.step == UnlockStep.DONE -> Color(0xFF239B56)
        else -> MaterialTheme.colorScheme.secondary
    }
    val text = state.error ?: state.status.pick(language)
    if (text.isNotBlank()) {
        Text(text, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun InstructionCard() {
    SketchCard {
        SectionTitle("03", tr("ИНСТРУКЦИЯ", "INSTRUCTIONS"))
        val steps = listOf(
            tr("Войдите в Mi Account на этом телефоне.", "Sign in to Mi Account on this phone."),
            tr("Переведите целевой телефон в fastboot: выключите и зажмите Громкость− и Питание.", "Put the target phone into fastboot: power it off, then hold Volume Down and Power."),
            tr("Подключите его к этому телефону через OTG-кабель.", "Connect it to this phone with an OTG cable."),
            tr("Нажмите «Найти устройство» и разрешите доступ к USB.", "Tap “Find device” and allow USB access."),
            tr("Нажмите «Разблокировать» и подтвердите стирание данных.", "Tap “Unlock” and confirm data wipe."),
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
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (language == "ru") MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text("RU", fontWeight = FontWeight.Black) }
                        OutlinedButton(
                            onClick = { onLanguage("en") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
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
                                shape = RoundedCornerShape(12.dp),
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
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (state.proxy.type == ProxyType.NONE) MiOrangeSoft else Color.Transparent,
                            ),
                        ) { Text(tr("ВЫКЛ", "OFF")) }
                        OutlinedButton(
                            onClick = { onProxy(state.proxy.copy(type = ProxyType.HTTP)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
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
