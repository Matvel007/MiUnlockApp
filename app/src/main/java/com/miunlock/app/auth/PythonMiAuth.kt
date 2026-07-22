package com.miunlock.app.auth

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PythonLoginResult(
    val needsVerification: Boolean = false,
    val verificationMethods: List<String> = emptyList(),
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
}
