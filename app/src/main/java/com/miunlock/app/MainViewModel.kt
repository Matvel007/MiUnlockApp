package com.miunlock.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.RunPhase
import com.miunlock.app.data.AuthDump
import com.miunlock.app.data.DebugLog
import com.miunlock.app.unlock.AccountStatus
import com.miunlock.app.unlock.StatusCheck
import com.miunlock.app.unlock.UnlockApi
import com.miunlock.app.unlock.UnlockOutcome
import com.miunlock.app.data.LogTag
import com.miunlock.app.service.UnlockService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MiUnlockApp
    private val drafts = MutableStateFlow(DraftState())
    private val snackbar = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val proxyStatus = MutableStateFlow<String?>(null)
    /** Есть ли сохранённый токен телефона: по нему читаются часы ожидания. */
    private val hasDeviceToken = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            hasDeviceToken.value = app.container.unlockStore.deviceToken() != null
            // Дамп собираем сразу: лог копируют и без нажатия «Проверить»,
            // а без сессии в нём нечего разбирать.
            refreshAuthDump()
        }
    }

    /** Обновляет блок сессии в журнале: по нему запрос повторяется вне телефона. */
    suspend fun refreshAuthDump() {
        val settings = app.container.settingsStore.current()
        val unlock = app.container.unlockStore.session()
        val saved = app.container.unlockStore.deviceToken()
        val dump = buildString {
            if (settings.credentials.isValid) {
                appendLine(AuthDump.forApply(settings.credentials, settings.proxy))
            }
            if (unlock.isValid) {
                appendLine(AuthDump.forUnlock(unlock, saved, app.container.unlockStore.proxy()))
            }
        }.trimEnd()
        if (dump.isNotBlank()) DebugLog.setAuthDump(dump)
    }

    val state = combine(
        app.container.settingsStore.settings,
        UnlockService.snapshot,
        drafts,
        snackbar,
        busy,
    ) { settings, snapshot, draft, message, isBusy ->
        AppState(
            settings = settings,
            draftToken = draft.token ?: settings.credentials.serviceToken,
            draftDeviceId = draft.deviceId ?: settings.credentials.deviceId,
            snapshot = snapshot,
            isBusy = isBusy,
            snackbar = message,
        )
    }.combine(proxyStatus) { appState, currentProxyStatus ->
        appState.copy(proxyStatus = currentProxyStatus)
    }.combine(hasDeviceToken) { appState, token ->
        appState.copy(hasDeviceToken = token)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())

    fun dispatch(intent: AppIntent) {
        when (intent) {
            is AppIntent.SetToken -> drafts.value = drafts.value.copy(token = intent.value)
            is AppIntent.SetDeviceId -> drafts.value = drafts.value.copy(deviceId = intent.value)
            is AppIntent.SetDelay -> updateSettings { copy(delayMs = intent.value.coerceIn(0, 10_000)) }
            is AppIntent.SetProxyType -> updateSettings { copy(proxyType = intent.value) }.also { proxyStatus.value = "Прокси не проверен" }
            is AppIntent.SetProxyHost -> updateProxy { copy(host = intent.value) }
            is AppIntent.SetProxyPort -> updateProxy { copy(port = intent.value.toIntOrNull() ?: 0) }
            is AppIntent.SetProxyUsername -> updateProxy { copy(username = intent.value) }
            is AppIntent.SetProxyPassword -> updateProxy { copy(password = intent.value) }
            is AppIntent.SetAutoResume -> updateSettings { copy(autoResume = intent.value) }
            is AppIntent.SetVibration -> updateSettings { copy(vibration = intent.value) }
            is AppIntent.SetHideRetryLaterHint -> updateSettings { copy(hideRetryLaterHint = intent.value) }
            is AppIntent.SetStatusNotifications -> updateSettings { copy(statusNotifications = intent.value) }
            is AppIntent.SetLanguage -> updateSettings { copy(language = intent.value) }
            AppIntent.Save -> saveCredentials()
            AppIntent.Start -> start()
            AppIntent.Stop -> UnlockService.stop(getApplication())
            AppIntent.CheckNow -> checkNow()
            AppIntent.CheckProxy -> checkProxy()
            AppIntent.DismissMessage -> snackbar.value = null
        }
    }

    private fun saveCredentials() = viewModelScope.launch {
        val current = state.value
        val updated = current.settings.copy(
            credentials = current.settings.credentials.copy(
                serviceToken = current.draftToken.trim(),
                deviceId = current.draftDeviceId.trim(),
            )
        )
        app.container.settingsStore.save(updated)
        drafts.value = DraftState()
        snackbar.value = "Данные сохранены локально"
    }

    private fun start() = viewModelScope.launch {
        saveDraftSilently()
        if (!state.value.settings.credentials.isValid &&
            (state.value.draftToken.isBlank() || state.value.draftDeviceId.isBlank())) {
            snackbar.value = "Сначала укажите serviceToken и deviceId"
            return@launch
        }
        DebugLog.add("Запуск фоновой подачи заявки")
        UnlockService.start(getApplication())
    }

    /**
     * Проверка при нажатии «Проверить»: измеряет задержку и ставит смещение.
     * Если телефон уже подключали и сохранён токен — дополнительно спрашивает
     * остаток ожидания. Mi Community как источник статуса не используется:
     * при пустом uid он отвечает дефолтом и вводит в заблуждение.
     */
    private fun checkNow() = viewModelScope.launch {
        busy.value = true
        DebugLog.add("Запуск проверки")
        saveDraftSilently()
        val savedSettings = app.container.settingsStore.current()
        val credentials = savedSettings.credentials.takeIf { it.isValid }
            ?: Credentials(state.value.draftToken, state.value.draftDeviceId)
        if (!credentials.isValid) {
            snackbar.value = "Сначала укажите данные аккаунта"
            busy.value = false
            return@launch
        }
        try {
            DebugLog.setAuthDump(AuthDump.forApply(credentials, savedSettings.proxy))
            val check = app.container.api.measureState(credentials, savedSettings.proxy)
            app.container.settingsStore.save(savedSettings.copy(delayMs = check.recommendedDelayMs))
            if (savedSettings.proxy.isEnabled) proxyStatus.value = "Прокси подключён"
            DebugLog.add("RTT: ${check.samplesMs.joinToString(", ")} мс")
            DebugLog.add("Смещение: ${check.recommendedDelayMs} мс (быстрая половина RTT / 2 + запас)")

            // Часы знает только unlock-сервер и только по токену устройства.
            // Нет токена — не гадаем, показываем одно смещение.
            val saved = app.container.unlockStore.deviceToken()
            val stored = app.container.unlockStore.session()
            val status = if (saved != null && stored.isValid) {
                val session = app.container.sessionRefresher.refresh(stored)
                if (saved.uid.isNotBlank() && session.userId.isNotBlank() && saved.uid != session.userId) {
                    // Токен читали под другим аккаунтом: ответ был бы про чужой.
                    DebugLog.add("токен от uid ${saved.uid}, сессия ${session.userId} — часы не спрашиваю")
                    null
                } else {
                    val outcome = StatusCheck(UnlockApi(app.container.unlockStore.proxy())).byToken(session, saved)
                    val hours = (outcome as? UnlockOutcome.Failure)?.waitHours
                    when {
                        hours != null -> AccountStatus(granted = true, waitHours = hours, source = "token")
                        outcome is UnlockOutcome.Success -> AccountStatus(granted = true, source = "token")
                        else -> null
                    }
                }
            } else {
                DebugLog.add("токена телефона нет — остаток ожидания не спрашиваю")
                null
            }
            snackbar.value = when {
                status == null -> "Смещение: ${check.recommendedDelayMs} мс"
                status.waitHours != null -> "Осталось ${status.waitHours} ч · смещение ${check.recommendedDelayMs} мс"
                status.granted -> "Разрешение есть · смещение ${check.recommendedDelayMs} мс"
                else -> "Смещение: ${check.recommendedDelayMs} мс"
            }
            hasDeviceToken.value = app.container.unlockStore.deviceToken() != null
        } catch (error: Exception) {
            if (state.value.settings.proxy.isEnabled) proxyStatus.value = "Прокси недоступен"
            DebugLog.error(LogTag.API, "Ошибка проверки: ${errorSummary(error)}")
            snackbar.value = "Ошибка проверки: ${proxyError(error)}"
        } finally {
            busy.value = false
        }
    }

    private fun checkProxy() = viewModelScope.launch {
        val proxy = state.value.settings.proxy
        busy.value = true
        DebugLog.add("Проверка ${proxy.type.name} прокси")
        try {
            app.container.api.checkProxy(proxy)
            proxyStatus.value = "Прокси подключён"
            DebugLog.add("Прокси подключён")
        } catch (error: Exception) {
            proxyStatus.value = "Прокси недоступен"
            DebugLog.error(LogTag.API, "Ошибка прокси: ${errorSummary(error)}")
            snackbar.value = "Ошибка прокси: ${proxyError(error)}"
        } finally {
            busy.value = false
        }
    }

    private suspend fun saveDraftSilently() {
        val s = state.value
        if (s.draftToken != s.settings.credentials.serviceToken ||
            s.draftDeviceId != s.settings.credentials.deviceId) {
            app.container.settingsStore.save(
                s.settings.copy(credentials = s.settings.credentials.copy(
                    serviceToken = s.draftToken.trim(), deviceId = s.draftDeviceId.trim()
                ))
            )
            drafts.value = DraftState()
        }
    }

    private fun updateSettings(transform: com.miunlock.app.domain.UserSettings.() -> com.miunlock.app.domain.UserSettings) {
        // База — из хранилища, а не из state: до первой эмиссии там дефолты,
        // и сохранение затёрло бы токен и deviceId.
        viewModelScope.launch {
            val current = app.container.settingsStore.current()
            app.container.settingsStore.save(current.transform())
        }
    }

    private fun updateProxy(transform: com.miunlock.app.domain.ProxySettings.() -> com.miunlock.app.domain.ProxySettings) {
        proxyStatus.value = "Прокси не проверен"
        updateSettings {
            when (proxyType) {
                com.miunlock.app.domain.ProxyType.HTTP -> copy(httpProxy = httpProxy.transform())
                com.miunlock.app.domain.ProxyType.NONE -> this
            }
        }
    }

    private fun proxyError(error: Exception): String = error.message ?: "неизвестная"

    private fun errorSummary(error: Exception): String = error::class.simpleName ?: "неизвестная ошибка"

    private data class DraftState(val token: String? = null, val deviceId: String? = null)
}
