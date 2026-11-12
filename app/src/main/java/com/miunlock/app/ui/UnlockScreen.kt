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
import com.miunlock.app.adb.AdbClient
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
    /** Идёт переход в fastboot через ADB — второй запуск запрещён. */
    val switching: Boolean = false,
    val step: UnlockStep? = null,
    val status: Bilingual = Bilingual("", ""),
    val error: String? = null,
    /** Код ответа сервера, если он был: 10005, 20036 и т. п. */
    val errorCode: Int? = null,
    /** Сколько часов ждать, если сервер назвал срок в тексте отказа. */
    val waitHours: Int? = null,
    val confirm: ClearConfirm? = null,
) {
    data class ClearConfirm(val cleanOrNot: Boolean, val notice: String)
}

private val HOURS = Regex("""(?<!\d)(\d{1,4})(?!\d)\s*(?:hour|hr|час)""", RegexOption.IGNORE_CASE)

/** Срок ожидания из текста ответа сервера: «wait 71 hours», «71 час». */
internal fun waitHoursIn(message: String): Int? =
    HOURS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..10_000 }

class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val store = UnlockStore(app)
    private val usb = FastbootUsb(app)
    private val adb = AdbClient(app, java.io.File(app.filesDir, "adb"))

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
        // Полный список USB — в журнал: по нему видно, в каком режиме целевой телефон.
        DebugLog.add(adb.allDevicesDiagnostics())
        val found = usb.candidates()
        if (found.isEmpty()) {
            state = state.copy(
                device = null,
                deviceLabel = "",
                error = null,
                errorCode = null,
                waitHours = null,
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
        state = state.copy(error = null, errorCode = null, waitHours = null)
    }

    /**
     * Переводит подключённый телефон в fastboot через ADB, чтобы не заставлять
     * человека зажимать кнопки. Нужна включённая отладка по USB; при первом
     * подключении телефон спросит разрешение на своём экране.
     */
    fun rebootToFastboot() {
        // Один ADB-канал: второй параллельный запуск подрался бы за него же.
        if (state.running || state.switching) return
        val found = adb.candidates()
        if (found.isEmpty()) {
            val alreadyFastboot = usb.candidates().isNotEmpty()
            state = state.copy(
                error = null,
                status = if (alreadyFastboot) {
                    Bilingual(
                        "Телефон уже в fastboot — нажмите «НАЙТИ»",
                        "The phone is already in fastboot — tap “FIND”",
                    )
                } else {
                    Bilingual(
                        "Телефон не найден по ADB. Включите «Отладку по USB» и на самом телефоне выберите режим USB «Передача файлов» (не «Только зарядка»). Либо переведите в fastboot кнопками.",
                        "Phone not found over ADB. Enable “USB debugging” and set the target phone’s USB mode to “File transfer” (not “Charging only”). Or enter fastboot with the buttons.",
                    )
                },
            )
            DebugLog.add(t("Разблокировка: нет устройства с отладкой", "Unlock: no device with USB debugging"))
            DebugLog.add(adb.allDevicesDiagnostics())
            return
        }
        val device = found.first()
        state = state.copy(
            error = null,
            switching = true,
            status = Bilingual("Готовлю переход в fastboot…", "Preparing to boot into fastboot…"),
        )
        viewModelScope.launch {
            DebugLog.add(adb.diagnostics(device))
            val granted = awaitPermission(device)
            if (!granted) {
                state = state.copy(
                    switching = false,
                    error = t("Нет доступа к USB-устройству", "No USB permission"),
                    status = Bilingual("Доступ к USB отклонён", "USB access denied"),
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    adb.open(device).getOrThrow()
                    adb.connect().getOrThrow()
                    adb.rebootToBootloader().getOrThrow()
                }
            }
            adb.close()
            result.fold(
                onSuccess = {
                    DebugLog.add(t("Разблокировка: телефон переведён в fastboot", "Unlock: phone rebooted into fastboot"))
                    state = state.copy(
                        switching = false,
                        status = Bilingual(
                            "Телефон перезагружается в fastboot, подождите…",
                            "The phone is rebooting into fastboot, please wait…",
                        ),
                    )
                    // Загрузчик поднимается не мгновенно: даём ему время и ищем сами.
                    repeat(6) {
                        delay(3_000)
                        if (usb.candidates().isNotEmpty()) {
                            scanDevice()
                            return@launch
                        }
                    }
                    state = state.copy(
                        switching = false,
                        status = Bilingual(
                            "Не дождался fastboot — нажмите «НАЙТИ»",
                            "Fastboot did not appear — tap “FIND”",
                        ),
                    )
                },
                onFailure = { error ->
                    DebugLog.add(t("Разблокировка: ADB ошибка — ", "Unlock: ADB error — ") + (error.message ?: "?"))
                    state = state.copy(
                        switching = false,
                        error = error.message,
                        status = Bilingual(
                            "Не удалось перевести в fastboot",
                            "Could not switch to fastboot",
                        ),
                    )
                },
            )
        }
    }

    private suspend fun awaitPermission(device: android.hardware.usb.UsbDevice): Boolean =
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                adb.requestPermission(device) { granted -> cont.resume(granted) }
            }
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

        state = state.copy(
            running = true,
            error = null,
            errorCode = null,
            waitHours = null,
            status = Bilingual("Подготовка…", "Preparing…"),
        )
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
                    errorCode = outcome.code,
                    // Сервер называет срок прямо в тексте: «you can unlock in 71 hours».
                    // Вытаскиваем его, чтобы показать человеку часы, а не простыню.
                    waitHours = waitHoursIn(outcome.message),
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
    val language = LocalLanguage.current
    LaunchedEffect(language) {
        vm.setLanguage(language)
        vm.refreshSession()
    }
    UnlockScreenContent(
        state = vm.state,
        language = language,
        onBack = onBack,
        onLogin = {
            context.startActivity(
                Intent(context, com.miunlock.app.auth.MiLoginActivity::class.java)
                    .putExtra("mode", "unlock")
                    .putExtra("language", language),
            )
        },
        onScan = vm::scanDevice,
        onFastboot = vm::rebootToFastboot,
        onUnlock = vm::startUnlock,
        onAnswerConfirm = vm::answerConfirm,
        onZone = vm::setZone,
        onProxy = vm::saveProxy,
        onLanguage = onLanguage,
        onLogout = vm::logout,
    )
}
