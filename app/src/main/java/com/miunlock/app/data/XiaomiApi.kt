package com.miunlock.app.data

import android.util.Log
import com.miunlock.app.domain.ApplyResult
import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.StateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class XiaomiApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun checkState(credentials: Credentials): StateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(STATE_URL).headers(credentials).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            parseState(response.readJson())
        }
    }

    suspend fun warmUp(credentials: Credentials) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(STATE_URL).headers(credentials).get().build()
            client.newCall(request).execute().close()
        }
        Unit
    }

    suspend fun apply(credentials: Credentials): ApplyResult = withContext(Dispatchers.IO) {
        val body = "{\"is_retry\":true}".toRequestBody(JSON)
        val request = Request.Builder().url(APPLY_URL).headers(credentials).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            parseApply(response.readJson())
        }
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

    private fun parseState(root: JSONObject): StateResult {
        val code = root.optInt("code", -1)
        if (code != 0) return StateResult(-1, errorMessage(code, root.optString("msg", "Ошибка API")))
        val data = root.optJSONObject("data") ?: return StateResult(-1, "Пустой ответ сервера")
        val deadline = data.optString("deadline_format")
        return when {
            data.optInt("is_pass") == 1 -> StateResult(1, "Доступ к разблокировке уже получен до $deadline", deadline)
            data.optInt("button_state") == 1 -> StateResult(2, "Можно подать заявку", deadline)
            data.optInt("button_state") == 2 -> StateResult(3, "Ошибка аккаунта. Повторите после $deadline", deadline)
            else -> StateResult(4, "Аккаунт должен быть зарегистрирован более 30 дней", deadline)
        }
    }

    private fun parseApply(root: JSONObject): ApplyResult {
        val serverTime = root.optLong("ts", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        val code = root.optInt("code", -1)
        if (code != 0) return ApplyResult(-1, errorMessage(code, root.optString("msg", "Ошибка API")), serverTime)
        val data = root.optJSONObject("data") ?: return ApplyResult(-1, "Пустой ответ сервера", serverTime)
        val deadline = data.optString("deadline_format")
        return when (data.optInt("apply_result")) {
            1 -> ApplyResult(1, "Заявка успешно подана", serverTime, true)
            2, 4 -> ApplyResult(2, "Ошибка аккаунта. Повторите после $deadline", serverTime)
            3 -> ApplyResult(3, "Лимит заявок исчерпан. Повторите после $deadline (GMT+8)", serverTime)
            5 -> ApplyResult(4, "Заявка отклонена. Попробуйте позже", serverTime)
            6 -> ApplyResult(5, "Повторите через минуту", serverTime)
            7 -> ApplyResult(6, "Повторите позже", serverTime)
            else -> ApplyResult(-1, "Неизвестный ответ Xiaomi", serverTime)
        }
    }

    private fun errorMessage(code: Int, fallback: String): String = when (code) {
        100001 -> "Ошибка параметров"
        100002 -> "Ошибка CSRF-токена"
        100003 -> "Операция не выполнена"
        100004 -> "Требуется повторный вход"
        100009 -> "Аккаунт заблокирован"
        else -> "$fallback (код $code)"
    }

    private fun Response.readJson(): JSONObject {
        val raw = body?.string().orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { error ->
            Log.e(TAG, "Invalid Xiaomi API JSON code=$code body=${raw.take(160)}", error)
            throw error
        }
    }

    companion object {
        private const val TAG = "XiaomiApi"
        private const val STATE_URL = "https://sgp-api.buy.mi.com/bbs/api/global/user/bl-switch/state"
        private const val APPLY_URL = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
