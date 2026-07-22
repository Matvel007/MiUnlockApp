package com.miunlock.app.domain

import java.time.Instant

data class Credentials(
    val serviceToken: String = "",
    val deviceId: String = "",
    val versionName: String = "5.4.11",
    val versionCode: Int = 500411,
) {
    val isValid: Boolean get() = serviceToken.isNotBlank() && deviceId.isNotBlank()
}

data class UserSettings(
    val credentials: Credentials = Credentials(),
    val delayMs: Int = 500,
    val autoResume: Boolean = false,
    val vibration: Boolean = true,
    val statusNotifications: Boolean = true,
    val language: String = "ru",
)

enum class RunPhase { IDLE, CHECKING, WAITING, WARMING, SENDING, SUCCESS, ERROR, STOPPED }

data class ServiceSnapshot(
    val phase: RunPhase = RunPhase.IDLE,
    val message: String = "Готово к запуску",
    val target: Instant? = null,
    val lastServerTime: Instant? = null,
    val lastUpdated: Instant = Instant.now(),
    val attempt: Int = 0,
)

data class StateResult(
    val code: Int,
    val message: String,
    val deadline: String = "",
)

data class ApplyResult(
    val code: Int,
    val message: String,
    val serverTime: Instant? = null,
    val successful: Boolean = false,
)

sealed interface AppIntent {
    data class SetToken(val value: String) : AppIntent
    data class SetDeviceId(val value: String) : AppIntent
    data class SetDelay(val value: Int) : AppIntent
    data class SetAutoResume(val value: Boolean) : AppIntent
    data class SetVibration(val value: Boolean) : AppIntent
    data class SetStatusNotifications(val value: Boolean) : AppIntent
    data class SetLanguage(val value: String) : AppIntent
    data object Save : AppIntent
    data object Start : AppIntent
    data object Stop : AppIntent
    data object CheckNow : AppIntent
    data object DismissMessage : AppIntent
}

data class AppState(
    val settings: UserSettings = UserSettings(),
    val draftToken: String = "",
    val draftDeviceId: String = "",
    val snapshot: ServiceSnapshot = ServiceSnapshot(),
    val isBusy: Boolean = false,
    val snackbar: String? = null,
)
