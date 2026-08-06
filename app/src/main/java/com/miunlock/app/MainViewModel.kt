package com.miunlock.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.RunPhase
import com.miunlock.app.data.DebugLog
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

    private fun checkNow() = viewModelScope.launch {
        busy.value = true
        DebugLog.add("Запуск проверки аккаунта")
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
            val check = app.container.api.measureState(credentials, savedSettings.proxy)
            app.container.settingsStore.save(savedSettings.copy(delayMs = check.recommendedDelayMs))
            if (savedSettings.proxy.isEnabled) proxyStatus.value = "Прокси подключён"
            DebugLog.add("RTT: ${check.samplesMs.joinToString(", ")} мс")
            DebugLog.add("Смещение: ${check.recommendedDelayMs} мс (быстрая половина RTT / 2 + запас)")
            DebugLog.add("Проверка завершена: RTT ${check.averageRttMs} мс, смещение ${check.recommendedDelayMs} мс, код ${check.state.code}")
            val statusText = when (check.state.code) {
                1 -> "🎉 РАЗРЕШЕНИЕ ЕСТЬ! Доступ выдан до ${check.state.deadline}"
                2 -> "📋 Разрешения НЕТ (можно подать заявку). Смещение: ${check.recommendedDelayMs} мс (средний RTT ${check.averageRttMs} мс)"
                3 -> "⛔ Разрешения НЕТ. Ошибка аккаунта (повторите после ${check.state.deadline})"
                4 -> "⛔ Разрешения НЕТ. Аккаунт должен быть зарегистрирован более 30 дней"
                else -> "${check.state.message}. Смещение: ${check.recommendedDelayMs} мс"
            }
            snackbar.value = statusText
        } catch (error: Exception) {
            if (state.value.settings.proxy.isEnabled) proxyStatus.value = "Прокси недоступен"
            DebugLog.add("Ошибка проверки: ${errorSummary(error)}")
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
            DebugLog.add("Ошибка прокси: ${errorSummary(error)}")
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
        viewModelScope.launch { app.container.settingsStore.save(state.value.settings.transform()) }
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
