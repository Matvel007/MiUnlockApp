package com.miunlock.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miunlock.app.domain.AppIntent
import com.miunlock.app.domain.AppState
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.RunPhase
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())

    fun dispatch(intent: AppIntent) {
        when (intent) {
            is AppIntent.SetToken -> drafts.value = drafts.value.copy(token = intent.value)
            is AppIntent.SetDeviceId -> drafts.value = drafts.value.copy(deviceId = intent.value)
            is AppIntent.SetDelay -> updateSettings { copy(delayMs = intent.value.coerceIn(0, 10_000)) }
            is AppIntent.SetAutoResume -> updateSettings { copy(autoResume = intent.value) }
            is AppIntent.SetVibration -> updateSettings { copy(vibration = intent.value) }
            is AppIntent.SetStatusNotifications -> updateSettings { copy(statusNotifications = intent.value) }
            is AppIntent.SetLanguage -> updateSettings { copy(language = intent.value) }
            AppIntent.Save -> saveCredentials()
            AppIntent.Start -> start()
            AppIntent.Stop -> UnlockService.stop(getApplication())
            AppIntent.CheckNow -> checkNow()
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
        UnlockService.start(getApplication())
    }

    private fun checkNow() = viewModelScope.launch {
        saveDraftSilently()
        val credentials = state.value.settings.credentials.takeIf { it.isValid }
            ?: Credentials(state.value.draftToken, state.value.draftDeviceId)
        if (!credentials.isValid) {
            snackbar.value = "Сначала укажите данные аккаунта"
            return@launch
        }
        UnlockService.check(getApplication())
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

    private data class DraftState(val token: String? = null, val deviceId: String? = null)
}
