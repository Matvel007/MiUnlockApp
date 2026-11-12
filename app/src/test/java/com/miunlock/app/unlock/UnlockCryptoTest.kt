package com.miunlock.app.unlock

import org.json.JSONObject
import com.miunlock.app.unlock.UnlockCrypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Эталонные значения получены независимой реализацией на Python
 * (openssl enc -aes-128-cbc -nopad + hmac/sha1 из stdlib). Совпадение с ними
 * значит, что подпись совпадает с оригинальными клиентами.
 */
class UnlockCryptoTest {

    /** base64("0123456789abcdef") — 16-байтовый ключ AES. */
    private val ssecurity = Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray(StandardCharsets.US_ASCII))

    @Test
    fun `ключ ssecurity разбирается в 16 байт`() {
        assertEquals(16, Base64.getDecoder().decode(ssecurity).size)
    }

    @Test
    fun `подпись nonce совпадает с эталоном`() {
        val signed = UnlockCrypto.sign("/api/v2/nonce", mapOf("r" to "abcdefghijklmnop"), ssecurity)
        assertEquals(
            "XFv1P9ZNVnpcSmiLr6qagzk9mbNl8+I8ZiNAhzYdai4a5siB1WbPx0YtlPWwCd8y",
            signed.params["sign"],
        )
        assertEquals("rL9oqbyaxIk982Izm6yjIkL9PaU=", signed.signature)
    }

    @Test
    fun `подпись ahaUnlock с data совпадает с эталоном`() {
        val data = JSONObject()
            .put("product", "alioth")
            .put("operate", "unlock")
            .toString()
        val signed = UnlockCrypto.sign(
            "/api/v3/ahaUnlock",
            mapOf("appId" to "1", "nonce" to "NONCE123", "data" to data),
            ssecurity,
        )
        assertEquals(
            "xJSzERpFGMO/m/TbpkCQQWCpeCvIK5K4psHm4GetYMAfhR1vH7yzuGGeweYG/G9t",
            signed.params["sign"],
        )
        assertEquals("8oPs+vjXvQtyo8t3ZcVfGPzxBE0=", signed.signature)
    }

    @Test
    fun `в подписанные параметры добавляются sid sign и signature но не ssecurity`() {
        val signed = UnlockCrypto.sign("/api/v2/nonce", mapOf("r" to "abcdefghijklmnop"), ssecurity)
        assertTrue(signed.params.containsKey("sid"))
        assertTrue(signed.params.containsKey("sign"))
        assertTrue(signed.params.containsKey("signature"))
        // sid передаётся зашифрованным, как и остальные параметры.
        val key = Base64.getDecoder().decode(ssecurity)
        val sid = String(UnlockCrypto.aesDecrypt(key, Base64.getDecoder().decode(signed.params.getValue("sid"))))
        assertEquals(UnlockCrypto.SID, sid)
        // ssecurity входит в строку подписи, но в запрос не уходит.
        assertFalse(signed.params.containsKey("ssecurity"))
        assertFalse(signed.params.values.contains(ssecurity))
    }

    @Test
    fun `параметры шифруются по одному и не совпадают с открытым текстом`() {
        val signed = UnlockCrypto.sign("/api/v2/nonce", mapOf("r" to "abcdefghijklmnop"), ssecurity)
        val encrypted = signed.params.getValue("r")
        assertFalse(encrypted.contains("abcdefghijklmnop"))
        val key = Base64.getDecoder().decode(ssecurity)
        val plain = UnlockCrypto.aesDecrypt(key, Base64.getDecoder().decode(encrypted))
        assertEquals("abcdefghijklmnop", String(plain, StandardCharsets.UTF_8))
    }

    @Test
    fun `ответ сервера расшифровывается до JSON`() {
        val body = "Pf2pu9ZFqWS5nkGPvg5qOMl26gEX1jnoMIIVr/OH0kKj6/ZF13cK3evev7hJuIqC"
        val json = UnlockCrypto.decryptResponse(body, ssecurity)
        assertEquals(0, json.getInt("code"))
        assertEquals("TESTNONCE", json.getString("nonce"))
    }

    @Test
    fun `md5 от deviceId даёт pcId в hex`() {
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592",
            UnlockCrypto.md5Hex("hello"),
        )
    }

    @Test
    fun `hexToBytes разбирает чётную строку и отвергает нечётную`() {
        val bytes = UnlockCrypto.hexToBytes("00ff10")
        assertEquals(3, bytes.size)
        assertEquals(0x00, bytes[0].toInt() and 0xff)
        assertEquals(0xff, bytes[1].toInt() and 0xff)
        assertEquals(0x10, bytes[2].toInt() and 0xff)
        assertTrue(runCatching { UnlockCrypto.hexToBytes("abc") }.isFailure)
    }

    @Test
    fun `toHex обратен hexToBytes`() {
        val original = byteArrayOf(0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals(original.toList(), UnlockCrypto.hexToBytes(original.toHex()).toList())
    }
}
