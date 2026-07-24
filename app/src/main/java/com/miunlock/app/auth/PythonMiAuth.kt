package com.miunlock.app.auth

import android.content.Context
import android.util.Base64
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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

    suspend fun login(user: String, password: String, sid: String, language: String): PythonLoginResult {
        call("set_language", language)
        return callLogin("login", user, password, sid)
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

    suspend fun sendCode(method: String) {
        val result = call("send_code", method)
        requireOk(result)
    }

    suspend fun verifyCode(method: String, code: String, sid: String): PythonLoginResult =
        callLogin("verify_code", method, code, sid)

    suspend fun exchange(sid: String): Pair<String, String> {
        val result = call("exchange", sid)
        requireOk(result)
        return result.getString("token") to result.getString("deviceId")
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
        qrClient(proxy, timeoutMs).newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("QR long-poll HTTP ${response.code}")
            val raw = response.body?.string().orEmpty()
            JSONObject(raw.removePrefix("&&&START&&&"))
            val setCookies = JSONArray(response.headers.values("Set-Cookie"))
            val result = call("qr_apply_cookies", setCookies.toString())
            requireOk(result)
            QrPollResult(scanned = true, expired = false)
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

    private fun qrClient(proxy: ProxySettings, timeoutMs: Long): OkHttpClient {
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
        return builder.build()
    }
}
