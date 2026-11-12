package com.miunlock.app.auth

import android.content.Context
import android.util.Base64
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import com.miunlock.app.domain.ProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class PythonLoginResult(
    val needsVerification: Boolean = false,
    val verificationMethods: List<String> = emptyList(),
)

/** Данные сервисной сессии Mi Account. */
data class ServiceLogin(
    val serviceToken: String,
    val deviceId: String,
    val userId: String,
    val ssecurity: String,
    val cookies: Map<String, String>,
)

data class QrLoginData(
    val loginUrl: String,
    val lp: String,
    val timeoutMs: Long,
    val tips: String,
    val cookieNames: List<String>,
    val cookieHeader: String,
)

data class QrAuthData(
    val value: String,
    val cookieNames: List<String>,
)

data class QrPollResult(
    val scanned: Boolean,
    val expired: Boolean,
)

class PythonMiAuth(context: Context) {
    private val module = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance().getModule("mi_auth")
    }

    /**
     * @param safeAddress включает проверку безопасного адреса. Сервис
     * разблокировки требует её включённой: от этого зависят `serviceParam`
     * и `_sign`, а с ними и приём кода подтверждения.
     */
    suspend fun login(
        user: String,
        password: String,
        sid: String,
        language: String,
        safeAddress: Boolean = false,
    ): PythonLoginResult {
        call("set_language", language)
        return callLogin("login", user, password, sid, if (safeAddress) "true" else "false")
    }

    suspend fun setProxy(proxy: ProxySettings) {
        val result = call(
            "set_proxy",
            if (proxy.isEnabled) proxy.host else "",
            if (proxy.isEnabled) proxy.port.toString() else "",
            if (proxy.isEnabled) proxy.username else "",
            if (proxy.isEnabled) proxy.password else "",
        )
        requireOk(result)
    }

    suspend fun sendCode(method: String): JSONObject {
        val result = call("send_code", method)
        // Ответ проверки квоты пишем в лог целиком: без него не понять,
        // это настоящий лимит или сервер ответил ошибкой, а мы приняли её за ноль.
        if (result.has("quota")) {
            DebugLog.add(LogLevel.INFO, LogTag.AUTH, "квота $method: ${result.getJSONObject("quota")}")
        }
        // Запрет частых попыток отдаём наверх: там покажем, сколько ждать.
        if (result.optInt("retryAfter", 0) > 0) {
            DebugLog.add(LogLevel.WARN, LogTag.AUTH, "отправка $method отложена: ${result.optString("error")}")
            return result
        }
        requireOk(result)
        return result
    }

    /**
     * Остаток попыток по способам подтверждения. Сервер считает почту и SMS
     * отдельно, поэтому выбирать способ вслепую нельзя.
     */
    suspend fun quotas(methods: List<String>): Map<String, Int> = withContext(Dispatchers.IO) {
        val result = call("quotas", *methods.toTypedArray())
        requireOk(result)
        val raw = result.optJSONObject("quotas") ?: return@withContext emptyMap()
        buildMap {
            raw.keys().forEach { key ->
                val quota = raw.optJSONObject(key)
                DebugLog.add(LogLevel.INFO, LogTag.AUTH, "квота $key: $quota")
                val info = quota?.optString("info").orEmpty()
                put(key, info.toIntOrNull() ?: -1)
            }
        }
    }

    suspend fun verifyCode(method: String, code: String, sid: String): PythonLoginResult =
        callLogin("verify_code", method, code, sid)

    suspend fun exchange(sid: String): Pair<String, String> {
        val result = call("exchange", sid)
        requireOk(result)
        return result.getString("token") to result.getString("deviceId")
    }

    /** Полный набор данных сервисной сессии — нужен режиму разблокировки. */
    suspend fun exchangeFull(sid: String): ServiceLogin {
        val result = call("exchange", sid)
        // Диагностика обмена: какие варианты запроса пробовали и что ответил сервер.
        result.optJSONObject("debug")?.let { d ->
            val attempts = d.optJSONArray("attempts")?.let { arr ->
                (0 until arr.length()).joinToString("; ") { i ->
                    val a = arr.optJSONObject(i)
                    if (a == null) "" else {
                        "${a.optString("variant")}: код ${a.opt("code")} " +
                            "nonce ${a.optBoolean("nonce")} ssecurity ${a.optBoolean("ssecurity")} " +
                            "result ${a.opt("result")}"
                    }
                }
            }.orEmpty()
            DebugLog.add(
                LogLevel.INFO,
                LogTag.AUTH,
                "обмен $sid: ключ ${d.optString("keySource")}, cookies ${d.opt("cookies")} | $attempts",
            )
            if (!d.optBoolean("hasNonce")) {
                DebugLog.warn(
                    LogTag.AUTH,
                    "поля ответа: ${d.optJSONArray("keys")?.let { a -> List(a.length()) { a.optString(it) } }}",
                )
            }
        }
        requireOk(result)
        val cookies = result.optJSONObject("cookies")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.orEmpty()
        return ServiceLogin(
            serviceToken = result.getString("token"),
            deviceId = result.getString("deviceId"),
            userId = result.optString("userId"),
            ssecurity = result.optString("ssecurity"),
            cookies = cookies,
        )
    }

    suspend fun qrLogin(authDataJson: String): QrLoginData {
        val result = call("qr_request", authDataJson)
        requireOk(result)
        return QrLoginData(
            loginUrl = result.getString("loginUrl"),
            lp = result.getString("lp"),
            timeoutMs = result.getInt("timeout") * 1000L,
            tips = result.optString("tips"),
            cookieNames = result.optJSONArray("cookieNames")
                ?.let { items -> List(items.length()) { items.getString(it) } }
                .orEmpty(),
            cookieHeader = result.optString("cookieHeader"),
        )
    }

    suspend fun qrAuthData(sid: String): QrAuthData {
        val result = call("qr_auth_data", sid)
        requireOk(result)
        return QrAuthData(
            value = result.getString("authData"),
            cookieNames = result.optJSONArray("cookieNames")
                ?.let { items -> List(items.length()) { items.getString(it) } }
                .orEmpty(),
        )
    }

    suspend fun qrPoll(lp: String, timeoutMs: Long, cookieHeader: String, proxy: ProxySettings): QrPollResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(lp)
            .header("User-Agent", "offici5l/migate")
            .header("Accept", "application/json;charset=UTF-8")
            .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
            .get()
            .build()
        try {
            qrClient(proxy, timeoutMs).newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("QR long-poll HTTP ${response.code}")
                val raw = response.body?.string().orEmpty()
                JSONObject(raw.removePrefix("&&&START&&&"))
                val setCookies = JSONArray(response.headers.values("Set-Cookie"))
                val result = call("qr_apply_cookies", setCookies.toString())
                requireOk(result)
                QrPollResult(scanned = true, expired = false)
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            // Long-poll просто истёк — это штатно: покажем новый QR, а не ошибку.
            QrPollResult(scanned = false, expired = true)
        }
    }

    suspend fun qrPng(url: String): ByteArray {
        val result = call("qr_png", url)
        requireOk(result)
        return Base64.decode(result.getString("pngBase64"), Base64.DEFAULT)
    }

    private suspend fun callLogin(name: String, vararg args: String): PythonLoginResult {
        val result = call(name, *args)
        requireOk(result)
        val methods = result.optJSONArray("verification")
            ?.let { array -> List(array.length()) { array.getString(it) } }
            .orEmpty()
        return PythonLoginResult(methods.isNotEmpty(), methods)
    }

    private suspend fun call(name: String, vararg args: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(module.callAttr(name, *args).toString())
    }

    private fun requireOk(result: JSONObject) {
        if (!result.optBoolean("ok")) error(result.optString("error", "Xiaomi error"))
    }

    /** Клиент переиспользуется: long-poll идёт циклом, новый пул на каждый круг — утечка потоков. */
    private var qrClientCache: Pair<String, OkHttpClient>? = null

    private fun qrClient(proxy: ProxySettings, timeoutMs: Long): OkHttpClient {
        val key = "${proxy.type}|${proxy.host}|${proxy.port}|${proxy.username}|$timeoutMs"
        qrClientCache?.let { (cachedKey, client) -> if (cachedKey == key) return client }
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutMs + 5_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (proxy.isEnabled) {
            require(proxy.isValid) { "Укажите корректные адрес и порт прокси" }
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            if (proxy.username.isNotBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", OkHttpCredentials.basic(proxy.username, proxy.password))
                        .build()
                }
            }
        }
        val client = builder.build()
        qrClientCache = key to client
        return client
    }
}
