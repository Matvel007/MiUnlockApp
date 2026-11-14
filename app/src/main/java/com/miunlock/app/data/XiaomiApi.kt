package com.miunlock.app.data

import com.miunlock.app.domain.ApplyResult
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.ProxyType
import com.miunlock.app.domain.StateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sqrt

class XiaomiApi {
    /**
     * Статус аккаунта в Mi Community.
     *
     * Только общий путь `bbs/api/global`: региональные (`/ru/`, `/in/`…)
     * существуют, но нашу сессию не принимают — отвечают `100004 need login`,
     * потому что она выдана под `18n_bbs_global`. Проверено на живом ответе.
     */
    suspend fun checkState(
        credentials: Credentials,
        proxy: ProxySettings = ProxySettings(),
    ): StateResult = withContext(Dispatchers.IO) {
        // Отпечаток сессии: по нему видно, ТОЙ ЛИ учёткой спросили статус.
        DebugLog.add(
            "state сессия: deviceId …${credentials.deviceId.takeLast(6)}, " +
                "uid ${credentials.userId.ifBlank { "?" }}, " +
                "токен ${credentials.serviceToken.length} симв., клиент ${credentials.versionName}/${credentials.versionCode}",
        )
        DebugLog.add("Xiaomi state request через ${routeName(proxy)}")
        val request = Request.Builder().url(STATE_URL).headers(credentials).get().build()
        client(proxy).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            // Тело — единственное, что объясняет статус: без него приходится
            // гадать по коду, а человеку нечего показать.
            DebugLog.add("Xiaomi state response: HTTP ${response.code}, ${raw.length} байт: ${raw.take(300)}")
            if (!response.isSuccessful) {
                DebugLog.error(LogTag.API, "state HTTP ${response.code}: ${raw.take(400)}")
                error("HTTP ${response.code}")
            }
            parseState(JSONObject(raw))
        }
    }

    suspend fun warmUp(credentials: Credentials, proxy: ProxySettings = ProxySettings()) = withContext(Dispatchers.IO) {
        DebugLog.add("Прогрев Xiaomi-соединения через ${routeName(proxy)}")
        runCatching {
            val request = Request.Builder().url(STATE_URL).headers(credentials).get().build()
            client(proxy).newCall(request).execute().use { response ->
                DebugLog.trace(LogTag.API, "прогрев: HTTP ${response.code}")
            }
        }
        Unit
    }

    suspend fun apply(credentials: Credentials, proxy: ProxySettings = ProxySettings()): ApplyResult = withContext(Dispatchers.IO) {
        DebugLog.add("Отправка заявки Xiaomi через ${routeName(proxy)}")
        val body = "{\"is_retry\":true}".toRequestBody(JSON)
        val request = Request.Builder().url(APPLY_URL).headers(credentials).post(body).build()
        client(proxy).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            DebugLog.add("Ответ заявки Xiaomi: HTTP ${response.code}, ${raw.length} байт")
            if (!response.isSuccessful) {
                DebugLog.error(LogTag.API, "apply HTTP ${response.code}: ${raw.take(400)}")
                error("HTTP ${response.code}")
            }
            DebugLog.trace(LogTag.API, "apply body: ${raw.take(600)}")
            parseApply(JSONObject(raw))
        }
    }

    suspend fun measureState(
        credentials: Credentials,
        proxy: ProxySettings,
    ): ConnectionCheck = withContext(Dispatchers.IO) {
        require(proxy.isValid) { "Укажите корректные адрес и порт прокси" }
        var lastState: StateResult? = null
        val samples = buildList {
            repeat(CHECK_SAMPLES) { index ->
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                lastState = checkState(credentials, proxy)
                val rtt = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                add(rtt)
                DebugLog.add("RTT ${index + 1}/$CHECK_SAMPLES: ${rtt.toInt()} мс")
            }
        }
        ConnectionCheck(
            state = requireNotNull(lastState),
            recommendedDelayMs = recommendedDelay(samples),
            averageRttMs = samples.average().toInt(),
            samplesMs = samples.map { it.toInt() },
        )
    }

    suspend fun checkProxy(proxy: ProxySettings): Boolean = withContext(Dispatchers.IO) {
        require(proxy.isEnabled && proxy.isValid) { "Укажите корректные адрес и порт прокси" }
        val request = Request.Builder().url(STATE_URL).get().build()
        client(proxy).newCall(request).execute().use { response -> response.code in 100..599 }
    }

    private fun routeName(proxy: ProxySettings) = if (proxy.isEnabled) "${proxy.type.name} proxy" else "без прокси"

    private fun client(proxy: ProxySettings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (!proxy.isEnabled) {
            return builder.build()
        }
        require(proxy.isValid) { "Укажите корректные адрес и порт прокси" }
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host.trim(), proxy.port)))
        if (proxy.username.isNotBlank()) {
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", OkHttpCredentials.basic(proxy.username, proxy.password))
                    .build()
            }
        }
        return builder.build()
    }

    private fun recommendedDelay(samples: List<Double>): Int {
        val fastest = samples.sorted().take((samples.size + 1) / 2)
        val average = fastest.average()
        val deviation = if (fastest.size > 1) sqrt(fastest.sumOf { (it - average) * (it - average) } / (fastest.size - 1)) else 0.0
        // A request reaches Xiaomi roughly halfway through RTT. Use only the fast half of samples
        // so a one-off slow response cannot push the submission too far ahead of midnight.
        return (ceil((average / 2 + deviation + SAFETY_MARGIN_MS).coerceIn(MIN_DELAY_MS.toDouble(), MAX_DELAY_MS.toDouble()) / DELAY_STEP_MS) * DELAY_STEP_MS).toInt()
    }

    private fun Request.Builder.headers(c: Credentials): Request.Builder = apply {
        header("User-Agent", "okhttp/4.12.0")
        header("Accept", "application/json")
        header("Content-Type", "application/json; charset=utf-8")
        header(
            "Cookie",
            "new_bbs_serviceToken=${c.serviceToken};versionCode=${c.versionCode};" +
                "versionName=${c.versionName};deviceId=${c.deviceId};"
        )
    }

    internal fun parseState(root: JSONObject): StateResult {
        val code = root.optInt("code", -1)
        if (code != 0) return StateResult(-1, errorMessage(code, serverMessage(root)))
        val data = root.optJSONObject("data") ?: return StateResult(-1, "Пустой ответ сервера")
        val deadline = data.optString("deadline_format")
        // Xiaomi отвечает парой is_pass + button_state. Разбираем все
        // сочетания явно: домысливать статус нельзя, иначе человеку
        // показывается причина, которой сервер не называл.
        val isPass = data.optInt("is_pass", -1)
        val buttonState = data.optInt("button_state", -1)
        return when {
            isPass == 1 -> StateResult(1, "Доступ к разблокировке уже получен до $deadline", deadline, isPass, buttonState)
            isPass == 4 && buttonState == 1 -> StateResult(2, "Можно подать заявку", deadline, isPass, buttonState)
            isPass == 4 && buttonState == 2 -> StateResult(3, "Ошибка аккаунта. Повторите после $deadline", deadline, isPass, buttonState)
            isPass == 4 && buttonState == 3 -> StateResult(4, "Аккаунт создан менее 30 дней назад", deadline, isPass, buttonState)
            else -> StateResult(
                5,
                "Xiaomi вернул незнакомый статус (is_pass=$isPass, button_state=$buttonState). Проверьте заявку в Mi Community",
                deadline,
                isPass,
                buttonState,
            )
        }
    }

    internal fun parseApply(root: JSONObject): ApplyResult {
        val serverTime = root.optLong("ts", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        val code = root.optInt("code", -1)
        // 100003 Xiaomi отдаёт, когда заявка, вероятно, уже принята: явного
        // подтверждения нет, но и отказа тоже. Это не ошибка — настоящий
        // ответ даёт проверка статуса аккаунта.
        if (code == CODE_MAYBE_APPLIED) {
            return ApplyResult(
                code = 7,
                message = "Xiaomi не подтвердил заявку явно. Проверяю статус аккаунта…",
                serverTime = serverTime,
                needsStateCheck = true,
            )
        }
        if (code != 0) return ApplyResult(-1, errorMessage(code, serverMessage(root)), serverTime)
        val data = root.optJSONObject("data") ?: return ApplyResult(-1, "Пустой ответ сервера", serverTime)
        val deadline = data.optString("deadline_format")
        val applyResult = data.optInt("apply_result", -1)
        return when (applyResult) {
            1 -> ApplyResult(1, "Заявка успешно подана", serverTime, true)
            2, 4 -> ApplyResult(2, "Ошибка аккаунта. Повторите после $deadline", serverTime)
            3 -> ApplyResult(3, "Лимит заявок исчерпан. Повторите после $deadline (GMT+8)", serverTime)
            // Остальные значения Xiaomi не описал. Гадать «отклонена» или
            // «повторите позже» нельзя — отправляем человека к статусу заявки.
            else -> ApplyResult(
                6,
                "Xiaomi не назвал результат (apply_result=$applyResult). Проверяю статус аккаунта…",
                serverTime,
                needsStateCheck = true,
            )
        }
    }

    /** Текст ошибки Xiaomi: в разных ответах он лежит в `msg` или `message`. */
    private fun serverMessage(root: JSONObject): String =
        root.optString("msg").ifBlank { root.optString("message") }.ifBlank { "Ошибка API" }

    private fun errorMessage(code: Int, fallback: String): String = when (code) {
        100001 -> "Ошибка параметров"
        100002 -> "Ошибка CSRF-токена"
        100003 -> "Операция не выполнена"
        100004, 10004 -> "Требуется повторный вход"
        100009 -> "Аккаунт заблокирован"
        else -> "$fallback (код $code)"
    }

    companion object {
        private const val STATE_URL = "https://sgp-api.buy.mi.com/bbs/api/global/user/bl-switch/state"
        private const val APPLY_URL = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
        private const val CODE_MAYBE_APPLIED = 100003
        private const val CHECK_SAMPLES = 7
        private const val MIN_DELAY_MS = 50
        private const val MAX_DELAY_MS = 3_000
        private const val DELAY_STEP_MS = 50
        private const val SAFETY_MARGIN_MS = 50
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class ConnectionCheck(
    val state: StateResult,
    val recommendedDelayMs: Int,
    val averageRttMs: Int,
    val samplesMs: List<Int>,
)
