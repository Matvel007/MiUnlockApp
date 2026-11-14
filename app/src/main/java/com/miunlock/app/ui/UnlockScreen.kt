package com.miunlock.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.miunlock.app.auth.PythonMiAuth
import com.miunlock.app.data.AuthDump
import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.fastboot.FastbootUsb
import com.miunlock.app.unlock.DISCONNECTED
import com.miunlock.app.unlock.FastbootCommands
import com.miunlock.app.unlock.SessionRefresher
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
    /** Код ответа сервера, если он был: 10005, 20036 и т. п. */
    val errorCode: Int? = null,
    /** Понятное объяснение известного кода ответа сервера. */
    val errorHint: Bilingual? = null,
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

/**
 * Известные коды ответа unlock.update.*. Сервер отвечает по-английски и общо,
 * человеку нужно объяснение: это не сбой приложения, а правило Xiaomi.
 */
internal fun unlockHint(code: Int?): Bilingual? = when (code) {
    10023, 20030 -> Bilingual(
        "У аккаунта закончился лимит разблокировок. С 2025 года Xiaomi разрешает одно устройство в год на аккаунт (в старом FAQ — одно в месяц и до четырёх в год).",
        "This account has used up its unlock quota. Since 2025 Xiaomi allows one device per year per account (the older FAQ says one per month, up to four per year).",
    )
    10005 -> Bilingual(
        "Сервер счёл запрос устаревшим. Нажмите «Разблокировать» ещё раз.",
        "The server treated the request as stale. Tap “Unlock” again.",
    )
    20031 -> Bilingual(
        "Xiaomi не видит привязки аккаунта к этому устройству. Приложение уже перебрало все " +
            "дата-центры Xiaomi, так что остаётся привязка. На телефоне: Настройки → " +
            "Дополнительные настройки → Для разработчиков → Статус разблокировки Mi → " +
            "«Добавить аккаунт и устройство». Аккаунт должен быть тот же, что здесь.",
        "Xiaomi does not see the account bound to this device. The app has already tried " +
            "every Xiaomi data centre, so what remains is the binding. On the phone: Settings → " +
            "Additional settings → Developer options → Mi Unlock status → " +
            "“Add account and device”. It must be the same account as here.",
    )
    20032 -> Bilingual(
        "Xiaomi не принял привязку. Проверьте, что на телефоне включён тот же аккаунт, " +
            "что и в приложении, вставлена SIM-карта и есть интернет.",
        "Xiaomi rejected the binding. Make sure the phone is signed in to the same account " +
            "as the app, with a SIM card inserted and internet available.",
    )
    20036 -> Bilingual(
        "Идёт обязательный отсчёт. Xiaomi считает его по паре «аккаунт + устройство» " +
            "и не даёт разблокировать раньше срока. Не добавляйте аккаунт заново — " +
            "отсчёт начнётся с нуля.",
        "The mandatory waiting period is running. Xiaomi counts it per account-and-device " +
            "pair and will not unlock earlier. Do not add the account again — the countdown " +
            "would restart from zero.",
    )
    20033 -> Bilingual(
        "Xiaomi ограничил аккаунт за подозрительную активность. Попробуйте включить " +
            "«Найти устройство» в настройках; если не поможет — нужен другой аккаунт.",
        "Xiaomi restricted this account for suspicious activity. Try enabling “Find device” " +
            "in settings; if that does not help, another account is required.",
    )
    20035 -> Bilingual(
        "Xiaomi требует более новую версию программы. Обновите приложение.",
        "Xiaomi requires a newer version of the tool. Update the app.",
    )
    20038 -> Bilingual(
        "Устройство заблокировано аккаунтом через «Найти устройство». Зайдите на " +
            "i.mi.com/mobile/find, выберите это устройство и отключите поиск.",
        "The device is locked by an account through “Find device”. Open " +
            "i.mi.com/mobile/find, select this device and turn off locating.",
    )
    20039 -> Bilingual(
        "Токен устройства неполный — в нём нет всех нужных данных. Перезагрузите " +
            "телефон в fastboot заново и повторите.",
        "The device token is incomplete — it lacks required data. Reboot the phone " +
            "into fastboot again and retry.",
    )
    20041 -> Bilingual(
        "К аккаунту не привязан номер телефона. Добавьте номер: " +
            "account.xiaomi.com/pass/serviceLogin?checkSafePhone=true",
        "The account has no phone number linked. Add one: " +
            "account.xiaomi.com/pass/serviceLogin?checkSafePhone=true",
    )
    20045 -> Bilingual(
        "Не совпадает регион: аккаунт и устройство проданы в разных странах. " +
            "Для китайского устройства нужен китайский аккаунт, для глобального — глобальный.",
        "Region mismatch: the account and the device come from different markets. " +
            "A Chinese device needs a Chinese account, a global device a global one.",
    )
    30002 -> Bilingual(
        "Аккаунт не привязан в статусе разблокировки на устройстве. " +
            "Настройки → Для разработчиков → Статус разблокировки Mi → «Добавить аккаунт и устройство».",
        "The account is not bound in Mi Unlock status on the device. " +
            "Settings → Developer options → Mi Unlock status → “Add account and device”.",
    )
    10013 -> Bilingual(
        "Устройство активировано слишком недавно либо не активировано. Нужна SIM-карта " +
            "с интернетом и около 7 дней с момента активации.",
        "The device was activated too recently or not activated at all. A SIM card with " +
            "internet is required, and about 7 days since activation.",
    )
    10000 -> Bilingual(
        "Сервер не принял параметры: неверный токен устройства или codename. " +
            "Перезапустите приложение и повторите.",
        "The server rejected the parameters: bad device token or codename. " +
            "Restart the app and retry.",
    )
    else -> null
}

class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val store = UnlockStore(app)
    private val refresher = SessionRefresher(app, store)
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
            // Начальное состояние читаем напрямую: так экран не зависит от того,
            // успел ли поток хранилища отдать первое значение при холодном старте.
            val saved = store.session()
            state = state.copy(session = saved, proxy = store.proxy())
        }
        // Дальше следим за хранилищем: вход на отдельном экране сразу виден здесь.
        viewModelScope.launch {
            store.sessionFlow().collect { session ->
                if (session != state.session) {
                    state = state.copy(session = session)
                }
            }
        }
    }

    fun saveSession(session: UnlockSession) {
        viewModelScope.launch {
            store.saveSession(session)
            state = state.copy(session = session)
        }
    }

    /**
     * Готовит сессию к запуску: обновляет cookies сервиса и уточняет зону.
     *
     * Токен unlock-домена живёт около часа — с истёкшим сервер отвечает 401
     * на первый же запрос. Обновление идёт по сохранённым cookies, поэтому
     * пароль и код не нужны. Зона определяет домен unlock.update.*.
     */
    private suspend fun prepareSession(session: UnlockSession): UnlockSession {
        if (session.cookies.isEmpty()) return session
        val auth = PythonMiAuth(getApplication())
        // Обновление cookies — общим компонентом: тот же путь нужен проверке
        // статуса в режиме подачи заявки.
        var prepared = refresher.refresh(session)
        auth.applySession(prepared.cookies)
        val account = auth.accountZone()
        if (account.zone.isNotBlank() && account.zone != prepared.zone) {
            DebugLog.add(
                t("Разблокировка: зона уточнена — ", "Unlock: zone resolved — ") +
                    "${prepared.zone} → ${account.zone} (регион ${account.region})",
            )
            prepared = prepared.copy(zone = account.zone)
        }
        if (prepared != session) store.saveSession(prepared)
        return prepared
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

    /** Ищет телефон в fastboot и запрашивает доступ. */
    fun scanDevice() {
        val found = usb.candidates()
        if (found.isEmpty()) {
            state = state.copy(
                device = null,
                deviceLabel = "",
                product = "",
                error = null,
                errorCode = null,
                errorHint = null,
                waitHours = null,
                status = Bilingual("Устройство не найдено", "No device found"),
            )
            DebugLog.add(t("Разблокировка: устройств в fastboot не найдено", "Unlock: no fastboot devices found"))
            DebugLog.add(usb.allDevicesDiagnostics())
            return
        }
        val dev = found.first()
        DebugLog.add(t("Разблокировка: найдено ${usb.deviceLabel(dev)}", "Unlock: found ${usb.deviceLabel(dev)}"))
        usb.requestPermission(dev) { granted ->
            if (!granted) {
                state = state.copy(
                    device = null,
                    deviceLabel = "",
                    product = "",
                    error = t("Нет доступа к USB-устройству", "No USB permission"),
                    status = Bilingual("Доступ к USB отклонён", "USB access denied"),
                )
                return@requestPermission
            }
            state = state.copy(
                device = dev,
                deviceLabel = usb.deviceLabel(dev),
                error = null,
                status = Bilingual("Читаю устройство…", "Reading the device…"),
            )
            // Codename читаем сразу: в fastboot USB-имена бесполезны — там
            // у всех «Google Android», а человеку нужно название модели.
            viewModelScope.launch {
                val codename = withContext(Dispatchers.IO) {
                    runCatching {
                        usb.open(dev).getOrThrow()
                        FastbootCommands(usb).product().getOrNull()
                    }.also { usb.close() }.getOrNull().orEmpty()
                }
                state = state.copy(
                    product = codename,
                    deviceLabel = usb.friendlyLabel(dev, codename),
                    status = Bilingual("Устройство подключено", "Device connected"),
                )
                DebugLog.add(
                    t(
                        "Разблокировка: codename $codename",
                        "Unlock: codename $codename",
                    ),
                )
            }
        }
    }

    fun clearError() {
        state = state.copy(error = null, errorCode = null, errorHint = null, waitHours = null)
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
            val session = runCatching { prepareSession(state.session) }.getOrDefault(state.session)
            if (session !== state.session) state = state.copy(session = session)
            DebugLog.setAuthDump(AuthDump.forUnlock(session, store.deviceToken(), state.proxy))
            val workflow = UnlockWorkflow(usb, UnlockApi(state.proxy))
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    workflow.run(
                        session = session,
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
                        // Токен сохраняем сразу: по нему статус потом спрашивается
                        // без телефона, в том числе из режима подачи заявки.
                        onToken = { product, token ->
                            store.saveDeviceToken(product, token, session.userId)
                            DebugLog.add(t("Токен устройства сохранён", "Device token saved"))
                        },
                    )
                }.getOrElse { UnlockOutcome.Failure(UnlockStep.DEVICE, it.message ?: "ошибка") }
            }

            state = when (outcome) {
                is UnlockOutcome.Success -> state.copy(
                    running = false,
                    status = if (outcome.message == DISCONNECTED) {
                        Bilingual(
                            "Разблокировано — телефон перезагружается",
                            "Unlocked — the phone is rebooting",
                        )
                    } else {
                        Bilingual("Разблокировано", "Unlocked")
                    },
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
                    errorHint = unlockHint(outcome.code),
                    // Срок приходит двумя путями: структурно в data.waitHour
                    // (так отвечает 20036) и текстом «you can unlock in 71 hours».
                    // Структурное поле надёжнее — текст разбираем как запасной.
                    waitHours = outcome.waitHours ?: waitHoursIn(outcome.message),
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
    LaunchedEffect(language) { vm.setLanguage(language) }
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
        onUnlock = vm::startUnlock,
        onAnswerConfirm = vm::answerConfirm,
        onZone = vm::setZone,
        onProxy = vm::saveProxy,
        onLanguage = onLanguage,
        onLogout = vm::logout,
        onGitHub = { openLink(context, REPO_URL) },
        onForum = { openLink(context, FORUM_URL) },
    )
}
