package com.miunlock.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("mi_unlock_settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val token = stringPreferencesKey("service_token")
        val deviceId = stringPreferencesKey("device_id")
        val versionName = stringPreferencesKey("version_name")
        val versionCode = intPreferencesKey("version_code")
        val delayMs = intPreferencesKey("delay_ms")
        val autoResume = booleanPreferencesKey("auto_resume")
        val vibration = booleanPreferencesKey("vibration")
        val statusNotifications = booleanPreferencesKey("status_notifications")
        val language = stringPreferencesKey("language")
        val wasRunning = booleanPreferencesKey("was_running")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            credentials = Credentials(
                serviceToken = p[Keys.token].orEmpty(),
                deviceId = p[Keys.deviceId].orEmpty(),
                versionName = p[Keys.versionName] ?: "5.4.11",
                versionCode = p[Keys.versionCode] ?: 500411,
            ),
            delayMs = (p[Keys.delayMs] ?: 500).coerceIn(0, 10_000),
            autoResume = p[Keys.autoResume] ?: false,
            vibration = p[Keys.vibration] ?: true,
            statusNotifications = p[Keys.statusNotifications] ?: true,
            language = p[Keys.language] ?: "ru",
        )
    }

    suspend fun current() = settings.first()

    suspend fun save(settings: UserSettings) {
        context.dataStore.edit { p ->
            p[Keys.token] = settings.credentials.serviceToken.trim()
            p[Keys.deviceId] = settings.credentials.deviceId.trim()
            p[Keys.versionName] = settings.credentials.versionName
            p[Keys.versionCode] = settings.credentials.versionCode
            p[Keys.delayMs] = settings.delayMs.coerceIn(0, 10_000)
            p[Keys.autoResume] = settings.autoResume
            p[Keys.vibration] = settings.vibration
            p[Keys.statusNotifications] = settings.statusNotifications
            p[Keys.language] = settings.language
        }
    }

    suspend fun markRunning(value: Boolean) {
        context.dataStore.edit { it[Keys.wasRunning] = value }
    }

    suspend fun shouldResume(): Boolean {
        val p = context.dataStore.data.first()
        return (p[Keys.autoResume] ?: false) && (p[Keys.wasRunning] ?: false)
    }
}
