package com.miunlock.app.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class FastbootCodecTest {

    private fun frame(text: String): Pair<ByteArray, Int> {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        return bytes to bytes.size
    }

    @Test
    fun `команда уходит как ASCII без префикса`() {
        val cmd = FastbootCodec.buildCommand("getvar:product")
        assertEquals("getvar:product", String(cmd, StandardCharsets.US_ASCII))
    }

    @Test
    fun `слишком длинная команда отвергается`() {
        val long = "x".repeat(FastbootCodec.MAX_COMMAND + 1)
        assertTrue(runCatching { FastbootCodec.buildCommand(long) }.isFailure)
    }

    @Test
    fun `OKAY разбирается вместе с payload`() {
        val (bytes, len) = frame("OKAYredwood")
        val parsed = FastbootCodec.parseFrame(bytes, len)
        assertTrue(parsed is FastbootFrame.Okay)
        assertEquals("redwood", (parsed as FastbootFrame.Okay).payload)
    }

    @Test
    fun `FAIL разбирается вместе с текстом ошибки`() {
        val (bytes, len) = frame("FAILGetVar Variable Not found")
        val parsed = FastbootCodec.parseFrame(bytes, len)
        assertTrue(parsed is FastbootFrame.Fail)
        assertEquals("GetVar Variable Not found", (parsed as FastbootFrame.Fail).payload)
    }

    @Test
    fun `INFO разбирается как отдельная строка`() {
        val (bytes, len) = frame("INFOtoken: 0a1b2c")
        val parsed = FastbootCodec.parseFrame(bytes, len)
        assertTrue(parsed is FastbootFrame.Info)
        assertEquals("token: 0a1b2c", (parsed as FastbootFrame.Info).payload)
    }

    @Test
    fun `DATA отдаёт длину в hex и в байтах`() {
        val (bytes, len) = frame("DATA00001000")
        val parsed = FastbootCodec.parseFrame(bytes, len) as FastbootFrame.Data
        assertEquals("00001000", parsed.hexLength)
        assertEquals(4096, parsed.length)
    }

    @Test
    fun `неизвестный префикс не теряется`() {
        val (bytes, len) = frame("XXXXpayload")
        val parsed = FastbootCodec.parseFrame(bytes, len) as FastbootFrame.Unknown
        assertEquals("XXXX", parsed.prefix)
    }

    @Test
    fun `кадр короче четырёх байт не роняет разбор`() {
        val parsed = FastbootCodec.parseFrame(byteArrayOf(0x4f, 0x4b), 2)
        assertTrue(parsed is FastbootFrame.Unknown)
    }

    @Test
    fun `длина для stage всегда восемь hex-цифр`() {
        assertEquals("00000010", FastbootCodec.hexLength(16))
        assertEquals("00010000", FastbootCodec.hexLength(65536))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `отрицательная длина — ошибка, а не кадр`() {
        FastbootCodec.hexLength(-1)
    }

    @Test
    fun `битая длина в DATA не превращается в ноль байт`() {
        val (bytes, len) = frame("DATAzzzz")
        assertTrue(FastbootCodec.parseFrame(bytes, len) is FastbootFrame.Unknown)
    }

    @Test
    fun `токен склеивается из нескольких INFO-строк`() {
        val merged = FastbootCodec.parseToken(
            "",
            listOf("token: AAAA", "token: BBBB", "token: CCCC"),
        )
        assertEquals("AAAABBBBCCCC", merged)
    }

    @Test
    fun `токен берётся из payload если INFO-строк нет`() {
        assertEquals("DEADBEEF", FastbootCodec.parseToken("OKAYtoken: DEADBEEF", emptyList()))
    }

    @Test
    fun `токен без префикса берётся как есть`() {
        // Реальный ответ Poco F3 на getvar:token (HyperOS): OKAY + base64 без "token:".
        val payload = "VQEBIAEQ1pIBsb2gbw3jwWH0RwyddgMGYWxpb3RoAgTGQX6l"
        assertEquals(payload, FastbootCodec.parseToken(payload, emptyList()))
    }

    @Test
    fun `сообщение об ошибке не принимается за токен`() {
        // FAILunknown command содержит пробел — это не токен.
        assertEquals("", FastbootCodec.parseToken("unknown command", emptyList()))
        assertEquals("", FastbootCodec.parseToken("GetVar Variable Not found", emptyList()))
    }

    @Test
    fun `без токена возвращается пустая строка`() {
        assertEquals("", FastbootCodec.parseToken("", emptyList()))
        assertEquals("", FastbootCodec.parseToken("", listOf("something else")))
        // Слишком короткая строка токеном не считается.
        assertEquals("", FastbootCodec.parseToken("OKAYabc", emptyList()))
    }

    @Test
    fun `codename берётся из payload`() {
        assertEquals("redwood", FastbootCodec.parseProduct("redwood", emptyList()))
    }

    @Test
    fun `codename берётся из INFO и очищается от ключа`() {
        assertEquals("alioth", FastbootCodec.parseProduct("", listOf("product: alioth")))
    }

    @Test
    fun `без codename результат пустой`() {
        assertEquals("", FastbootCodec.parseProduct("", listOf("product: ")))
    }

    @Test
    fun `произвольный getvar ищется без учёта регистра`() {
        assertEquals("unlocked", FastbootCodec.parseGetVar("unlocked", "", listOf("UNLOCKED: unlocked")))
        assertEquals("", FastbootCodec.parseGetVar("serialno", "", listOf("product: alioth")))
    }

    @Test
    fun `isOkay верен только для OKAY`() {
        assertTrue(FastbootResult(FastbootCodec.STATUS_OKAY, "").isOkay)
        assertFalse(FastbootResult(FastbootCodec.STATUS_FAIL, "").isOkay)
    }
}
