package com.miunlock.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
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
        val proxyType = stringPreferencesKey("proxy_type")
        val proxyHost = stringPreferencesKey("proxy_host")
        val proxyPort = intPreferencesKey("proxy_port")
        val proxyUsername = stringPreferencesKey("proxy_username")
        val proxyPassword = stringPreferencesKey("proxy_password")
        val httpProxyHost = stringPreferencesKey("http_proxy_host")
        val httpProxyPort = intPreferencesKey("http_proxy_port")
        val httpProxyUsername = stringPreferencesKey("http_proxy_username")
        val httpProxyPassword = stringPreferencesKey("http_proxy_password")
        val autoResume = booleanPreferencesKey("auto_resume")
        val vibration = booleanPreferencesKey("vibration")
        val statusNotifications = booleanPreferencesKey("status_notifications")
        val language = stringPreferencesKey("language")
        val wasRunning = booleanPreferencesKey("was_running")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        val proxyType = runCatching { ProxyType.valueOf(p[Keys.proxyType] ?: ProxyType.NONE.name) }
            .getOrDefault(ProxyType.NONE)
        val legacyProxy = ProxySettings(
            type = proxyType,
            host = p[Keys.proxyHost].orEmpty(),
            port = p[Keys.proxyPort] ?: 0,
            username = p[Keys.proxyUsername].orEmpty(),
            password = p[Keys.proxyPassword].orEmpty(),
        )
        UserSettings(
            credentials = Credentials(
                serviceToken = p[Keys.token].orEmpty(),
                deviceId = p[Keys.deviceId].orEmpty(),
                versionName = p[Keys.versionName] ?: "5.4.11",
                versionCode = p[Keys.versionCode] ?: 500411,
            ),
            delayMs = (p[Keys.delayMs] ?: 500).coerceIn(0, 10_000),
            proxyType = proxyType,
            httpProxy = ProxySettings(
                type = ProxyType.HTTP,
                host = p[Keys.httpProxyHost] ?: if (proxyType == ProxyType.HTTP) legacyProxy.host else "",
                port = p[Keys.httpProxyPort] ?: if (proxyType == ProxyType.HTTP) legacyProxy.port else 0,
                username = p[Keys.httpProxyUsername] ?: if (proxyType == ProxyType.HTTP) legacyProxy.username else "",
                password = p[Keys.httpProxyPassword] ?: if (proxyType == ProxyType.HTTP) legacyProxy.password else "",
            ),
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
            p[Keys.proxyType] = settings.proxyType.name
            p[Keys.httpProxyHost] = settings.httpProxy.host.trim()
            p[Keys.httpProxyPort] = settings.httpProxy.port.coerceIn(0, 65535)
            p[Keys.httpProxyUsername] = settings.httpProxy.username.trim()
            p[Keys.httpProxyPassword] = settings.httpProxy.password
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
