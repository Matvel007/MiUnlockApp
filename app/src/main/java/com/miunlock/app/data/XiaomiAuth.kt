package com.miunlock.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Replicates migate's get_service() + get_passtoken() sign exchange.
 *
 * Phase 1 (WebView): user logs in at
 *   https://account.xiaomi.com/pass/serviceLogin?sid=18n_bbs_global
 *   → cookies carry passToken, deviceId, userId.
 *
 * Phase 2 (API — this class):
 *   1) GET serviceLogin?_json=true&sid=...  → nonce, ssecurity, location
 *   2) GET {location}&clientSign=sha1(nonce&ssecurity) → service cookies
 */
class XiaomiAuth {

    data class NativeLoginResult(
        val needsVerification: Boolean = false,
        val verificationMethods: List<String> = emptyList(),
    )

    private class InMemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        fun clear() = store.clear()

        fun allCookies(): List<Cookie> = store.values.flatten()

        fun setCookies(host: String, raw: List<String>) {
            val list = mutableListOf<Cookie>()
            raw.forEach { line ->
                line.split(";").forEach { part ->
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) return@forEach
                    val name = trimmed.substring(0, eq)
                    val value = trimmed.substring(eq + 1)
                    list.add(
                        Cookie.Builder()
                            .domain(host)
                            .path("/")
                            .name(name)
                            .value(value)
                            .build()
                    )
                }
            }
            store[host] = list
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val matched = store.entries
                .filter { (domain, _) -> url.host == domain || url.host.endsWith(".$domain") }
                .flatMap { (_, cookies) -> cookies }

            return if (matched.isNotEmpty()) {
                matched
            } else if (url.host.endsWith(".buy.mi.com")) {
                store.values.flatten()
            } else {
                emptyList()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { incoming ->
                val domain = incoming.domain.removePrefix(".")
                val target = store.getOrPut(domain) { mutableListOf() }
                target.removeAll { it.name == incoming.name }
                target.add(incoming)
            }
        }
    }

    private fun newClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "XiaomiAuth"
        private val REQUIRED_PASS_TOKEN_COOKIES = setOf("deviceId", "passToken", "userId")
        const val SERVICE_LOGIN_URL =
            "https://account.xiaomi.com/pass/serviceLogin"
        private const val LONG_POLLING_URL =
            "https://account.xiaomi.com/longPolling/loginUrl"
        private const val SERVICE_LOGIN_AUTH2_URL =
            "https://account.xiaomi.com/pass/serviceLoginAuth2"
        private const val IDENTITY_LIST_URL = "https://account.xiaomi.com/identity/list"
        private const val SEND_EMAIL_TICKET_URL = "https://account.xiaomi.com/identity/auth/sendEmailTicket"
        private const val SEND_PHONE_TICKET_URL = "https://account.xiaomi.com/identity/auth/sendPhoneTicket"
        private const val VERIFY_EMAIL_URL = "https://account.xiaomi.com/identity/auth/verifyEmail"
        private const val VERIFY_PHONE_URL = "https://account.xiaomi.com/identity/auth/verifyPhone"
        private const val USER_QUOTA_URL = "https://account.xiaomi.com/identity/pass/sms/userQuota"
    }

    private val passportCookieJar = InMemoryCookieJar()
    private val passportClient = newClient(passportCookieJar)
    private var pollUrl: String? = null
    private var pollTimeoutSeconds: Int = 30
    private var nativeAuthData: Map<String, String>? = null
    private var nativeVerificationContext: String? = null

    suspend fun loginWithPassword(user: String, password: String, sid: String): NativeLoginResult = withContext(Dispatchers.IO) {
        passportCookieJar.clear()
        val serviceJson = passportClient.apiGet(queryUrl(SERVICE_LOGIN_URL, mapOf("sid" to sid, "_json" to "True")))
        val authData = mapOf(
            "sid" to sid,
            "_json" to "True",
            "serviceParam" to serviceJson.getString("serviceParam"),
            "qs" to serviceJson.getString("qs"),
            "callback" to serviceJson.getString("callback"),
            "_sign" to serviceJson.getString("_sign"),
            "user" to user.trim(),
            "hash" to MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it) },
        )
        nativeAuthData = authData
        finishNativeLogin(passportClient.apiPost(SERVICE_LOGIN_AUTH2_URL, authData), sid)
    }

    suspend fun sendNativeVerificationCode(method: String) = withContext(Dispatchers.IO) {
        check(nativeVerificationContext != null) { "Сначала войдите с логином и паролем" }
        val url = if (method == "PH") SEND_PHONE_TICKET_URL else SEND_EMAIL_TICKET_URL
        val quota = passportClient.apiPost(
            USER_QUOTA_URL,
            mapOf("addressType" to method, "contentType" to "160040", "_json" to "true"),
        )
        if (quota.optInt("info", 0) <= 0) error("Лимит отправки кодов Xiaomi исчерпан")

        val response = passportClient.apiPostEmpty(url)
        if (response.optInt("code", -1) != 0) {
            val detail = response.optString("tips")
                .ifBlank { response.optString("desc") }
                .ifBlank { "Код Xiaomi ${response.optInt("code")}" }
            error(detail)
        }
    }

    suspend fun verifyNativeCode(method: String, code: String, sid: String): NativeLoginResult = withContext(Dispatchers.IO) {
        val url = if (method == "PH") VERIFY_PHONE_URL else VERIFY_EMAIL_URL
        val verified = passportClient.apiPost(url, mapOf("ticket" to code.trim(), "trust" to "true", "_json" to "true"))
        if (verified.optInt("code", -1) != 0) error(verified.optString("desc", "Неверный код"))
        val location = verified.getString("location")
        val followUp = passportClient.getRedirectLocation(location)
        passportClient.rawGetWithoutRedirect(followUp)
        val authData = nativeAuthData ?: error("Сессия входа истекла")
        finishNativeLogin(passportClient.apiPost(SERVICE_LOGIN_AUTH2_URL, authData), sid)
    }

    private suspend fun finishNativeLogin(response: JSONObject, sid: String): NativeLoginResult {
        val code = response.optInt("code", -1)
        if (code == 70016) error("Неверный Xiaomi ID, email, телефон или пароль")
        if (code != 0 && !response.has("notificationUrl")) error(response.optString("desc", "Ошибка Xiaomi ($code)"))
        val notificationUrl = response.optString("notificationUrl")
        if (notificationUrl.isNotBlank()) {
            val context = notificationUrl.toHttpUrlOrNull()?.queryParameter("context")
                ?: error("Xiaomi не передал контекст подтверждения")
            nativeVerificationContext = context
            val options = passportClient.apiGet(queryUrl(IDENTITY_LIST_URL, mapOf("sid" to sid, "supportedMask" to "0", "context" to context)))
                .optJSONArray("options")
            val methods = buildList {
                for (index in 0 until (options?.length() ?: 0)) {
                    when (options?.optInt(index)) {
                        4 -> add("PH")
                        8 -> add("EM")
                    }
                }
            }
            if (methods.isEmpty()) error("Нет поддерживаемого способа подтверждения Xiaomi")
            return NativeLoginResult(needsVerification = true, verificationMethods = methods)
        }
        val cookies = passportCookieJar.allCookies().associate { it.name to it.value }
        val missing = REQUIRED_PASS_TOKEN_COOKIES.filter { cookies[it].isNullOrBlank() }
        if (missing.isNotEmpty()) error("Xiaomi не выдал ${missing.joinToString()}")
        return NativeLoginResult()
    }

    suspend fun preparePassportLogin(sid: String): String = withContext(Dispatchers.IO) {
        passportCookieJar.clear()
        Log.d(TAG, "serviceLogin _json start sid=$sid")
        val serviceJson = passportClient.apiGet(
            queryUrl(SERVICE_LOGIN_URL, mapOf("sid" to sid, "_json" to "True"))
        )
        Log.d(TAG, "serviceLogin _json keys=${serviceJson.keys().asSequence().toList()}")

        val params = mapOf(
            "sid" to sid,
            "_json" to "False",
            "serviceParam" to serviceJson.getString("serviceParam"),
            "qs" to serviceJson.getString("qs"),
            "callback" to serviceJson.getString("callback"),
            "_sign" to serviceJson.getString("_sign"),
        )
        val loginJson = passportClient.apiGet(queryUrl(LONG_POLLING_URL, params))
        Log.d(TAG, "longPolling loginUrl keys=${loginJson.keys().asSequence().toList()}")
        pollUrl = loginJson.getString("lp")
        pollTimeoutSeconds = loginJson.optInt("timeout", 30).coerceIn(5, 120)
        loginJson.getString("loginUrl")
    }

    suspend fun pollPassportToken(): Map<String, String> = withContext(Dispatchers.IO) {
        val url = pollUrl ?: error("URL проверки входа ещё не подготовлен")
        Log.d(TAG, "pollPassportToken url=${url.take(120)}")
        passportClient.apiGet(url, timeoutSeconds = pollTimeoutSeconds + 5L)
        passportCookieJar.allCookies().associate { it.name to it.value }.also { cookies ->
            val missing = listOf("deviceId", "passToken", "userId").filter { cookies[it].isNullOrBlank() }
            if (missing.isNotEmpty()) error("Вход ещё не завершён: нет ${missing.joinToString()}")
        }
    }

    fun currentPassportCookies(): Map<String, String> =
        passportCookieJar.allCookies().associate { it.name to it.value }

    suspend fun exchangePreparedServiceToken(sid: String): Map<String, String> {
        val passportCookies = passportCookieJar.allCookies()
            .associate { it.name to it.value }
        val missing = REQUIRED_PASS_TOKEN_COOKIES.filter { passportCookies[it].isNullOrBlank() }
        if (missing.isNotEmpty()) error("Вход ещё не завершён: нет ${missing.joinToString()}")

        // Mirrors migate.get_service(): only these three passport cookies are sent.
        val rawCookies = passportCookies
            .filterKeys { it in REQUIRED_PASS_TOKEN_COOKIES }
            .entries
            .joinToString("; ") { (key, value) -> "$key=$value" }
        return exchangeServiceToken(rawCookies, sid)
    }

    /**
     * @param passTokenCookies raw semicolon-separated cookie string harvested
     *        from the WebView after a successful Xiaomi Passport login.
     *        Must include: deviceId, passToken, userId.
     * @param sid              service id, e.g. "18n_bbs_global"
     * @return map with keys: serviceToken, new_bbs_serviceToken, deviceId,
     *         popRunToken, new_login, userId
     */
    suspend fun exchangeServiceToken(
        passTokenCookies: String,
        sid: String,
        vararg extraCookieUrls: String,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val cookieJar = InMemoryCookieJar().apply {
            setCookies("account.xiaomi.com", listOf(passTokenCookies))
        }
        val client = newClient(cookieJar)
        exchangeWithCookieJar(cookieJar, client, sid, passTokenCookies)
    }

    private suspend fun exchangeWithCookieJar(
        cookieJar: InMemoryCookieJar,
        client: OkHttpClient,
        sid: String,
        initialCookieHeader: String? = null,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val step1Url = queryUrl(SERVICE_LOGIN_URL, mapOf("sid" to sid, "_json" to "True"))
        Log.d(TAG, "exchange step1 start")
        val step1Json = client.apiGet(step1Url, cookieHeader = initialCookieHeader)

        val nonce = step1Json.optString("nonce", "").takeIf { it.isNotEmpty() }
        val ssecurity = step1Json.optString("ssecurity", "").takeIf { it.isNotEmpty() }
        val location = step1Json.optString("location", "").takeIf { it.isNotEmpty() }
        val cUserId = step1Json.optString("cUserId", "")
        val psecurity = step1Json.optString("psecurity", "")
        Log.d(
            TAG,
            "exchange step1 result code=${step1Json.optString("code")} nonce=${nonce != null} " +
                "ssecurity=${ssecurity != null} locationHost=${location?.toHttpUrlOrNull()?.host}",
        )

        if (!location.isNullOrBlank() && (nonce.isNullOrBlank() || ssecurity.isNullOrBlank())) {
            Log.d(TAG, "exchange direct location start location=${location.take(120)}")
            client.rawGet(location, allowHttpError = true)
            return@withContext cookieJar.allCookies().associate { it.name to it.value }.toMutableMap().also { harvested ->
                Log.d(TAG, "exchange direct cookies keys=${harvested.keys}")
                if (harvested["new_bbs_serviceToken"].isNullOrBlank()) {
                    error("Mi Community не выдал new_bbs_serviceToken через login-back")
                }
            }
        }

        if (nonce.isNullOrBlank() || ssecurity.isNullOrBlank() || location.isNullOrBlank()) {
            error("Неполный ответ сервера: nonce=$nonce ssecurity=${ssecurity?.take(4)} location=$location")
        }

        // Step 2: clientSign = base64(sha1("nonce={nonce}&{ssecurity}"))
        val rawSign = "nonce=$nonce&$ssecurity"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(rawSign.toByteArray(Charsets.UTF_8))
        val clientSign = encode(Base64.getEncoder().encodeToString(sha1))

        val step2Url = "$location&clientSign=$clientSign"
        Log.d(TAG, "exchange step2 start location=${location.take(120)}")
        client.rawGet(step2Url)

        val harvested = cookieJar.allCookies().associate { it.name to it.value }.toMutableMap()
        Log.d(TAG, "exchange signed cookies keys=${harvested.keys}")

        if (harvested["new_bbs_serviceToken"].isNullOrBlank()) {
            error("Не удалось получить serviceToken. Проверьте вход.")
        }

        harvested
    }

    private suspend fun OkHttpClient.apiGet(
        url: String,
        timeoutSeconds: Long = 15,
        cookieHeader: String? = null,
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json;charset=UTF-8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .apply {
                if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
            }
            .build()

        return newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} при запросе к Xiaomi")
            val raw = response.body?.string().orEmpty()
            JSONObject(if (raw.startsWith("&&&START&&&")) raw.substring(11) else raw)
        }
    }

    private fun OkHttpClient.apiPost(url: String, params: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Accept", "application/json;charset=UTF-8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(body)
            .build()
        return newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} при запросе к Xiaomi")
            val raw = response.body?.string().orEmpty()
            JSONObject(if (raw.startsWith("&&&START&&&")) raw.substring(11) else raw)
        }
    }

    private fun OkHttpClient.apiPostEmpty(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Accept", "application/json;charset=UTF-8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(FormBody.Builder().build())
            .build()
        return newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} при запросе к Xiaomi")
            val raw = response.body?.string().orEmpty()
            JSONObject(if (raw.startsWith("&&&START&&&")) raw.substring(11) else raw)
        }
    }

    private fun OkHttpClient.rawGet(url: String, allowHttpError: Boolean = false) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json;charset=UTF-8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        newCall(request).execute().use { response ->
            val chain = generateSequence(response) { it.priorResponse }
                .map { item -> "${item.code}:${item.headers.values("Set-Cookie").map { value -> value.substringBefore('=') }}" }
                .joinToString(" <- ")
            Log.d(TAG, "rawGet result chain=$chain")
            if (!response.isSuccessful && !allowHttpError) error("HTTP ${response.code} при сервисном входе Xiaomi")
            if (!response.isSuccessful) Log.d(TAG, "rawGet ignored HTTP ${response.code} url=${url.take(120)}")
        }
    }

    private fun OkHttpClient.getRedirectLocation(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Accept", "application/json;charset=UTF-8")
            .get()
            .build()
        return newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).execute().use { response ->
                response.header("Location") ?: error("Xiaomi не передал redirect после проверки кода")
            }
    }

    private fun OkHttpClient.rawGetWithoutRedirect(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "offici5l/migate")
            .header("Accept", "application/json;charset=UTF-8")
            .get()
            .build()
        newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).execute().use { response ->
                Log.d(TAG, "2fa follow-up code=${response.code} cookies=${response.headers.values("Set-Cookie").map { it.substringBefore('=') }}")
            }
    }

    private fun queryUrl(base: String, params: Map<String, String>): String =
        params.entries.joinToString(prefix = "$base?", separator = "&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
