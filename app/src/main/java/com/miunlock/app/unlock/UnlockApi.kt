package com.miunlock.app.unlock

import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogTag
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Результат запроса к unlock.update.*: код сервера плюс разобранный JSON. */
data class UnlockApiResult(val code: Int, val json: JSONObject) {
    val desc: String
        get() = json.optString("descEN").ifEmpty {
            json.optString("description").ifEmpty { "code $code" }
        }
    val isOk: Boolean get() = code == 0
}

data class DeviceClearInfo(val cleanOrNot: Boolean, val notice: String)

/**
 * Клиент unlock.update.*. Три вызова: nonce, device/clear, ahaUnlock.
 *
 * Подпись запросов — в [UnlockCrypto]. Здесь только транспорт и разбор.
 */
class UnlockApi(private val proxy: ProxySettings = ProxySettings()) {

    companion object {
        const val USER_AGENT = "XiaomiPCSuite"

        const val ZONE_CHINA = "China"
        const val ZONE_SINGAPORE = "Singapore"
        const val ZONE_INDIA = "India"
        const val ZONE_RUSSIA = "Russia"
        const val ZONE_EUROPE = "Europe"

        val DOMAINS = mapOf(
            ZONE_SINGAPORE to "https://unlock.update.intl.miui.com",
            ZONE_CHINA to "https://unlock.update.miui.com",
            ZONE_INDIA to "https://in-unlock.update.intl.miui.com",
            ZONE_RUSSIA to "https://ru-unlock.update.intl.miui.com",
            ZONE_EUROPE to "https://eu-unlock.update.intl.miui.com",
        )

        private val EUROPE = setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "EL",
            "HU", "IS", "IE", "IT", "LV", "LI", "LT", "LU", "MT", "NL", "NO", "PL",
            "PT", "RO", "SK", "SI", "ES", "SE", "UK", "GB",
        )

        /** Регион аккаунта -> зона. Неизвестное считаем Сингапуром, как и оригинал. */
        fun zoneForRegion(region: String?): String {
            val r = region?.trim()?.uppercase().orEmpty()
            return when {
                r == "CN" -> ZONE_CHINA
                r == "IN" -> ZONE_INDIA
                r == "RU" -> ZONE_RUSSIA
                EUROPE.contains(r) -> ZONE_EUROPE
                else -> ZONE_SINGAPORE
            }
        }

        fun domainForZone(zone: String): String = DOMAINS[zone] ?: DOMAINS.getValue(ZONE_SINGAPORE)
    }

    private val client: OkHttpClient by lazy { buildClient() }

    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (proxy.isEnabled && proxy.host.isNotBlank() && proxy.port in 1..65535) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
        }
        return b.build()
    }

    /** Одноразовый nonce. Живёт до первого использования. */
    suspend fun nonce(domain: String, ssecurity: String, cookies: Map<String, String>): UnlockApiResult =
        post(domain, "/api/v2/nonce", mapOf("r" to randomString(16)), ssecurity, cookies)

    /** Проверка: сотрутся ли данные при разблокировке. Съедает nonce. */
    suspend fun deviceClear(
        domain: String,
        product: String,
        nonce: String,
        ssecurity: String,
        cookies: Map<String, String>,
    ): UnlockApiResult = post(
        domain,
        "/api/v2/unlock/device/clear",
        mapOf("appId" to "1", "nonce" to nonce, "data" to JSONObject().put("product", product).toString()),
        ssecurity,
        cookies,
    )

    /** Главный вызов: сервер подписывает deviceToken и отдаёт encryptData. */
    suspend fun ahaUnlock(
        domain: String,
        product: String,
        deviceToken: String,
        nonce: String,
        ssecurity: String,
        deviceId: String,
        userId: String,
        cookies: Map<String, String>,
    ): UnlockApiResult {
        val deviceInfo = JSONObject()
            .put("boardVersion", "")
            .put("deviceName", "")
            .put("product", product)
            .put("socId", "")
        val data = JSONObject()
            .put("clientId", "2")
            .put("clientVersion", "7.6.727.43")
            .put("deviceInfo", deviceInfo)
            .put("deviceToken", deviceToken)
            .put("language", "en")
            .put("operate", "unlock")
            .put("pcId", UnlockCrypto.md5Hex(deviceId))
            .put("region", "")
            .put("uid", userId)
        return post(
            domain,
            "/api/v3/ahaUnlock",
            mapOf("appId" to "1", "nonce" to nonce, "data" to data.toString()),
            ssecurity,
            cookies,
        )
    }

    /** Разбирает `cleanOrNot`/`notice` из ответа device/clear. */
    fun parseClear(result: UnlockApiResult): DeviceClearInfo =
        DeviceClearInfo(result.json.optInt("cleanOrNot", 0) == 1, result.json.optString("notice"))

    private suspend fun post(
        domain: String,
        path: String,
        params: Map<String, String>,
        ssecurity: String,
        cookies: Map<String, String>,
    ): UnlockApiResult = withContext(Dispatchers.IO) {
        val signed = UnlockCrypto.sign(path, params, ssecurity)
        val url = buildString {
            append(domain).append(path)
            var first = true
            for ((k, v) in signed.params) {
                append(if (first) '?' else '&')
                first = false
                append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val builder = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(EMPTY))
            .header("User-Agent", USER_AGENT)
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            DebugLog.trace(
                LogTag.API,
                "POST $path: HTTP ${response.code}, ${body.length} байт",
            )
            if (!response.isSuccessful) {
                // Тело при ошибке — единственное, что объясняет причину:
                // неверная зона, истёкшая сессия, блокировка по IP.
                DebugLog.error(
                    LogTag.API,
                    "POST $path: HTTP ${response.code}, начало тела: ${body.take(200)}",
                )
                throw IllegalStateException("HTTP ${response.code} на $path")
            }
            if (body.isBlank()) {
                DebugLog.error(LogTag.API, "POST $path: пустой ответ")
                throw IllegalStateException("пустой ответ сервера")
            }
            val json = try {
                UnlockCrypto.decryptResponse(body, ssecurity)
            } catch (error: Exception) {
                DebugLog.error(
                    LogTag.API,
                    "POST $path: не расшифрован ответ, начало: ${body.take(200)}",
                )
                throw error
            }
            DebugLog.trace(LogTag.API, "POST $path: код ${json.optInt("code", -1)}")
            UnlockApiResult(json.optInt("code", -1), json)
        }
    }

    private fun randomString(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz"
        return buildString { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private val EMPTY = "application/octet-stream".toMediaType()

    /** Прокси нужен только если реально включён HTTP. */
    private val ProxySettings.isEnabled: Boolean
        get() = type != ProxyType.NONE
}
