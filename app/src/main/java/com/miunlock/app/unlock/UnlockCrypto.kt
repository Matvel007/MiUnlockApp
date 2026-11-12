package com.miunlock.app.unlock

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись запросов к unlock.update.*. Алгоритм восстановлен по двум независимым
 * открытым реализациям (Python и Kotlin) — они совпали между собой.
 *
 * Схема:
 * 1. `data` (если есть) кодируется в base64 от JSON-строки;
 * 2. по отсортированным ключам считается HMAC-SHA1 ключом приложения от
 *    "POST\n<путь>\n<k=v&...>", результат берётся **hex-строкой** (40 символов ASCII);
 * 3. эта hex-строка шифруется AES-128-CBC ключом из `ssecurity` с фиксированным IV —
 *    это `sign`;
 * 4. каждый параметр шифруется тем же ключом отдельно;
 * 5. `signature` = base64(sha1("POST&<путь>&<параметры>&sign=<sign>&<ssecurity>")).
 *
 * `ssecurity` в строку подписи входит голой строкой (без ключа), но в запрос не уходит.
 */
object UnlockCrypto {

    private val IV = "0102030405060708".toByteArray(StandardCharsets.US_ASCII)

    /** Ключ приложения, общий для всех клиентов Xiaomi. */
    private val SIGN_KEY =
        "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno"
            .toByteArray(StandardCharsets.US_ASCII)

    /** `sid`, который клиент обязан добавлять в каждый подписанный запрос. */
    const val SID = "miui_unlocktool_client"

    data class SignedParams(val params: Map<String, String>, val signature: String)

    fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(data)
    }

    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return cipher.doFinal(data)
    }

    fun hmacSha1(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(message)
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8)).toHex()

    fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "нечётная длина hex" }
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    /**
     * Подписывает параметры запроса.
     *
     * @param dataJson уже сериализованный JSON для параметра `data`, если он нужен.
     *                 Передаётся строкой, чтобы порядок ключей не зависел от JSONObject.
     */
    fun sign(path: String, params: Map<String, String>, ssecurity: String): SignedParams {
        val key = Base64.getDecoder().decode(ssecurity)
        val raw = LinkedHashMap<String, String>()
        for ((k, v) in params) raw[k] = v
        raw["sid"] = SID
        raw["data"]?.let { raw["data"] = b64(it.toByteArray(StandardCharsets.UTF_8)) }

        val order = raw.keys.sorted()
        val signParams = order.joinToString("&") { "$it=${raw[it]}" }
        val signStr = "POST\n$path\n$signParams"

        val signHashHex = hmacSha1(SIGN_KEY, signStr.toByteArray(StandardCharsets.UTF_8)).toHex()
        val currentSign = b64(aesEncrypt(key, signHashHex.toByteArray(StandardCharsets.US_ASCII)))

        val encoded = order.map { k ->
            "$k=" + b64(aesEncrypt(key, raw[k]!!.toByteArray(StandardCharsets.UTF_8)))
        }.toMutableList()
        encoded.add("sign=$currentSign")
        encoded.add(ssecurity)

        val sha1Input = "POST&$path&${encoded.joinToString("&")}"
        val signature = b64(sha1(sha1Input.toByteArray(StandardCharsets.UTF_8)))

        val out = LinkedHashMap<String, String>()
        for (k in order) out[k] = b64(aesEncrypt(key, raw[k]!!.toByteArray(StandardCharsets.UTF_8)))
        out["sign"] = currentSign
        out["signature"] = signature
        return SignedParams(out, signature)
    }

    /** Ответ сервера: base64 -> AES -> внутренний base64 -> JSON. */
    fun decryptResponse(body: String, ssecurity: String): JSONObject {
        val trimmed = body.trim()
        require(trimmed.isNotEmpty()) { "пустой ответ" }
        val key = Base64.getDecoder().decode(ssecurity)
        val plain = aesDecrypt(key, Base64.getDecoder().decode(trimmed))
        val inner = String(plain, StandardCharsets.UTF_8)
        val jsonBytes = Base64.getDecoder().decode(inner)
        return JSONObject(String(jsonBytes, StandardCharsets.UTF_8))
    }

    private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
}
