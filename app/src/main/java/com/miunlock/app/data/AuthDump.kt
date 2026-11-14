package com.miunlock.app.data

import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.unlock.SavedDeviceToken
import com.miunlock.app.unlock.UnlockSession

/**
 * Выгрузка сессии в журнал: по ней видно, ЧЕМ ходили, без самих секретов.
 *
 * Журнал копируют и пересылают, поэтому значения токенов и cookie сюда не
 * попадают: только имена и длины. Этого хватает, чтобы понять, полная ли
 * сессия и тот ли аккаунт, но повторять запросы по такому логу нельзя —
 * и это правильно.
 */
object AuthDump {

    /** Токен устройства — не секрет аккаунта: без него статус не спросить. */
    fun forApply(credentials: Credentials, proxy: ProxySettings): String = buildString {
        appendLine("apply.token=${masked(credentials.serviceToken)}")
        appendLine("apply.deviceId=${credentials.deviceId}")
        appendLine("apply.version=${credentials.versionName}/${credentials.versionCode}")
        appendLine("apply.uid=${credentials.userId}")
        appendLine("apply.proxy=${proxy.describe()}")
    }.trimEnd()

    fun forUnlock(
        session: UnlockSession,
        saved: SavedDeviceToken?,
        proxy: ProxySettings,
    ): String = buildString {
        appendLine("unlock.sid=${session.sid}")
        appendLine("unlock.userId=${session.userId}")
        appendLine("unlock.deviceId=${session.deviceId}")
        appendLine("unlock.ssecurity=${masked(session.ssecurity)}")
        appendLine("unlock.zone=${session.zone}")
        appendLine("unlock.stsDomain=${session.stsDomain}")
        appendLine("unlock.cookies=${namedCookies(session)}")
        if (saved != null) {
            appendLine("device.product=${saved.product}")
            appendLine("device.token=${saved.token}")
            appendLine("device.uid=${saved.uid}")
        }
        appendLine("unlock.proxy=${proxy.describe()}")
    }.trimEnd()

    /** Секрет не печатаем: только длину. По ней видно, что значение не пустое. */
    private fun masked(value: String): String =
        if (value.isBlank()) "нет" else "${value.length} симв."

    /** Имена cookie и длины: какие куски сессии на месте, без их содержимого. */
    private fun namedCookies(session: UnlockSession): String {
        val header = session.cookieHeader()
        if (header.isBlank()) return "нет"
        return header.split("; ")
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim()
                if (name.isBlank()) null else "$name:${part.substringAfter('=', "").length}"
            }
            .joinToString(", ")
    }

    private fun ProxySettings.describe(): String =
        if (type == com.miunlock.app.domain.ProxyType.NONE) "none" else "$type $host:$port"
}
