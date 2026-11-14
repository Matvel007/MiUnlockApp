package com.miunlock.app.unlock

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Авторизованная сессия режима разблокировки. Отдельная от режима заявки. */
data class UnlockSession(
    val serviceToken: String = "",
    val ssecurity: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val zone: String = UnlockApi.ZONE_SINGAPORE,
    /** Сервис, под который выдан токен. Пусто у старых сессий — они не годятся. */
    val sid: String = "",
    /**
     * Хост, который Xiaomi сам назвал в STS (`location`). Токен сервиса
     * привязан именно к нему, поэтому этот адрес пробуем первым.
     */
    val stsDomain: String = "",
) {
    /**
     * Признак рабочей сессии. Токен сервиса нужен не всем режимам: запросы
     * к unlock.update.* подписываются ключом `ssecurity`, а токен Xiaomi
     * выдаёт под своим именем для каждого сервиса. Поэтому решают ключ,
     * пользователь и cookies.
     */
    val isValid: Boolean
        get() = ssecurity.isNotBlank() && userId.isNotBlank() &&
            cookies.isNotEmpty() && sid.isNotBlank()

    /** Строка cookie для заголовка запроса. */
    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** Токен телефона, прочитанный из fastboot. */
data class SavedDeviceToken(val product: String, val token: String, val uid: String)

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
        val sid = stringPreferencesKey("unlock_sid")
        val stsDomain = stringPreferencesKey("unlock_sts_domain")
        /** Токен устройства из fastboot: по нему статус спрашивается без телефона. */
        val deviceProduct = stringPreferencesKey("unlock_device_product")
        val deviceToken = stringPreferencesKey("unlock_device_token")
        /** uid, под которым токен читали: статус принадлежит аккаунту. */
        val deviceTokenUid = stringPreferencesKey("unlock_device_token_uid")
        val proxyType = stringPreferencesKey("unlock_proxy_type")
        val proxyHost = stringPreferencesKey("unlock_proxy_host")
        val proxyPort = stringPreferencesKey("unlock_proxy_port")
        val proxyUser = stringPreferencesKey("unlock_proxy_user")
        val proxyPassword = stringPreferencesKey("unlock_proxy_password")
    }

    /**
     * Сессия как поток: DataStore сам сообщает об изменениях. Нужно, чтобы экран
     * обновлялся сразу после входа в аккаунт на отдельном экране, без перезахода.
     */
    fun sessionFlow(): Flow<UnlockSession> = context.unlockDataStore.data.map { p -> toSession(p) }

    suspend fun session(): UnlockSession = toSession(context.unlockDataStore.data.first())

    private fun toSession(p: Preferences): UnlockSession {
        return UnlockSession(
            serviceToken = p[Keys.token].orEmpty(),
            ssecurity = p[Keys.ssecurity].orEmpty(),
            userId = p[Keys.userId].orEmpty(),
            deviceId = p[Keys.deviceId].orEmpty(),
            cookies = decodeCookies(p[Keys.cookies].orEmpty()),
            zone = p[Keys.zone] ?: UnlockApi.ZONE_SINGAPORE,
            sid = p[Keys.sid].orEmpty(),
            stsDomain = p[Keys.stsDomain].orEmpty(),
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
            p[Keys.sid] = session.sid
            p[Keys.stsDomain] = session.stsDomain
        }
    }

    suspend fun clearSession() {
        context.unlockDataStore.edit { p ->
            p.remove(Keys.token)
            p.remove(Keys.ssecurity)
            p.remove(Keys.userId)
            p.remove(Keys.deviceId)
            p.remove(Keys.cookies)
            p.remove(Keys.sid)
        }
    }

    /** Запоминает токен телефона: по нему статус спрашивается без fastboot. */
    suspend fun saveDeviceToken(product: String, token: String, uid: String) {
        context.unlockDataStore.edit { p ->
            p[Keys.deviceProduct] = product
            p[Keys.deviceToken] = token
            p[Keys.deviceTokenUid] = uid
        }
    }

    /** Сохранённый токен телефона: null, если телефон ещё не подключали. */
    suspend fun deviceToken(): SavedDeviceToken? {
        val p = context.unlockDataStore.data.first()
        val product = p[Keys.deviceProduct].orEmpty()
        val token = p[Keys.deviceToken].orEmpty()
        if (product.isBlank() || token.isBlank()) return null
        return SavedDeviceToken(product, token, p[Keys.deviceTokenUid].orEmpty())
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
