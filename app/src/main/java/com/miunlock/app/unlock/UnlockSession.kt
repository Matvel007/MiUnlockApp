package com.miunlock.app.unlock

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import kotlinx.coroutines.flow.first

/** Авторизованная сессия режима разблокировки. Отдельная от режима заявки. */
data class UnlockSession(
    val serviceToken: String = "",
    val ssecurity: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val zone: String = UnlockApi.ZONE_SINGAPORE,
) {
    val isValid: Boolean
        get() = serviceToken.isNotBlank() && ssecurity.isNotBlank() && userId.isNotBlank()

    /** Строка cookie для заголовка запроса. */
    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

private val Context.unlockDataStore by preferencesDataStore("mi_unlock_unlock_mode")

/**
 * Хранилище сессии разблокировки. Своё хранилище, чтобы не пересекаться
 * с сессией заявки: выход из одного режима не должен выкидывать из другого.
 */
class UnlockStore(private val context: Context) {

    private object Keys {
        val token = stringPreferencesKey("unlock_service_token")
        val ssecurity = stringPreferencesKey("unlock_ssecurity")
        val userId = stringPreferencesKey("unlock_user_id")
        val deviceId = stringPreferencesKey("unlock_device_id")
        val cookies = stringPreferencesKey("unlock_cookies")
        val zone = stringPreferencesKey("unlock_zone")
        val proxyType = stringPreferencesKey("unlock_proxy_type")
        val proxyHost = stringPreferencesKey("unlock_proxy_host")
        val proxyPort = stringPreferencesKey("unlock_proxy_port")
        val proxyUser = stringPreferencesKey("unlock_proxy_user")
        val proxyPassword = stringPreferencesKey("unlock_proxy_password")
    }

    suspend fun session(): UnlockSession {
        val p = context.unlockDataStore.data.first()
        return UnlockSession(
            serviceToken = p[Keys.token].orEmpty(),
            ssecurity = p[Keys.ssecurity].orEmpty(),
            userId = p[Keys.userId].orEmpty(),
            deviceId = p[Keys.deviceId].orEmpty(),
            cookies = decodeCookies(p[Keys.cookies].orEmpty()),
            zone = p[Keys.zone] ?: UnlockApi.ZONE_SINGAPORE,
        )
    }

    suspend fun saveSession(session: UnlockSession) {
        context.unlockDataStore.edit { p ->
            p[Keys.token] = session.serviceToken
            p[Keys.ssecurity] = session.ssecurity
            p[Keys.userId] = session.userId
            p[Keys.deviceId] = session.deviceId
            p[Keys.cookies] = encodeCookies(session.cookies)
            p[Keys.zone] = session.zone
        }
    }

    suspend fun clearSession() {
        context.unlockDataStore.edit { p ->
            p.remove(Keys.token)
            p.remove(Keys.ssecurity)
            p.remove(Keys.userId)
            p.remove(Keys.deviceId)
            p.remove(Keys.cookies)
        }
    }

    suspend fun proxy(): ProxySettings {
        val p = context.unlockDataStore.data.first()
        val type = runCatching { ProxyType.valueOf(p[Keys.proxyType] ?: ProxyType.NONE.name) }
            .getOrDefault(ProxyType.NONE)
        return ProxySettings(
            type = type,
            host = p[Keys.proxyHost].orEmpty(),
            port = p[Keys.proxyPort]?.toIntOrNull() ?: 0,
            username = p[Keys.proxyUser].orEmpty(),
            password = p[Keys.proxyPassword].orEmpty(),
        )
    }

    suspend fun saveProxy(proxy: ProxySettings) {
        context.unlockDataStore.edit { p ->
            p[Keys.proxyType] = proxy.type.name
            p[Keys.proxyHost] = proxy.host.trim()
            p[Keys.proxyPort] = proxy.port.toString()
            p[Keys.proxyUser] = proxy.username.trim()
            p[Keys.proxyPassword] = proxy.password
        }
    }

    private fun encodeCookies(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}=${it.value}" }

    private fun decodeCookies(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }
}
