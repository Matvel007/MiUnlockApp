package com.miunlock.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

/**
 * Проверки формата ключа ADB. Ошибка здесь не падает с исключением, а просто
 * не даёт телефону авторизовать хост, поэтому проверяем структуру побайтно.
 *
 * Раскладка структуры: len(4) + n0inv(4) + n(256) + rr(256) + e(4) + " id\0".
 */
class AdbKeyTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "adbkey-test-${System.nanoTime()}")
    private val keys = AdbKey(dir)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private val modulusOffset = 8
    private val rrOffset = 8 + 256
    private val exponentOffset = 8 + 512

    @Test
    fun `публичный ключ имеет структуру adb`() {
        val pair = keys.loadOrCreate()
        val identity = "miunlockapp@android"
        val encoded = keys.encodePublic(pair.public as RSAPublicKey, identity)
        assertEquals(AdbKey.STRUCT_BYTES + identity.length + 2, encoded.size)
        // длина модуля в 32-битных словах
        assertEquals(AdbKey.KEY_WORDS, le32(encoded, 0))
        // экспонента 65537
        assertEquals(65537, le32(encoded, exponentOffset))
        // хвост: пробел, identity, NUL
        val tail = String(encoded, AdbKey.STRUCT_BYTES, encoded.size - AdbKey.STRUCT_BYTES, Charsets.US_ASCII)
        assertEquals(" $identity\u0000", tail)
    }

    @Test
    fun `n0inv согласован с модулем`() {
        val modulus = (keys.loadOrCreate().public as RSAPublicKey).modulus
        val n0 = modulus.and(BigInteger.valueOf(0xFFFFFFFFL)).toLong()
        val inv = keys.computeN0inv(modulus).toLong() and 0xFFFFFFFFL
        // n0 * n0inv ≡ -1 (mod 2^32)
        assertEquals(0xFFFFFFFFL, (n0 * inv) and 0xFFFFFFFFL)
    }

    @Test
    fun `R в квадрате лежит в структуре`() {
        val public = keys.loadOrCreate().public as RSAPublicKey
        val encoded = keys.encodePublic(public, "t")
        val rr = encoded.copyOfRange(rrOffset, rrOffset + 256)
        val value = BigInteger(1, rr.reversedArray())
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(public.modulus), value)
    }

    @Test
    fun `модуль в структуре совпадает с ключом`() {
        val public = keys.loadOrCreate().public as RSAPublicKey
        val encoded = keys.encodePublic(public, "t")
        val modulus = BigInteger(1, encoded.copyOfRange(modulusOffset, modulusOffset + 256).reversedArray())
        assertEquals(public.modulus, modulus)
    }

    @Test
    fun `подпись токена проверяется публичным ключом`() {
        val pair = keys.loadOrCreate()
        val token = ByteArray(20) { (it * 7).toByte() }
        val signature = keys.signToken(token, pair.private as RSAPrivateKey)
        assertEquals(256, signature.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.public)
        val block = cipher.doFinal(signature)
        // DigestInfo(15 байт) + SHA-1(20 байт)
        assertEquals(35, block.size)
        assertArrayEquals(
            MessageDigest.getInstance("SHA-1").digest(token),
            block.copyOfRange(15, 35),
        )
    }

    @Test
    fun `ключ переиспользуется между запусками`() {
        val first = keys.loadOrCreate().public as RSAPublicKey
        val second = AdbKey(dir).loadOrCreate().public as RSAPublicKey
        assertEquals(first.modulus, second.modulus)
        assertEquals(first.publicExponent, second.publicExponent)
        assertTrue(File(dir, "adbkey").exists())
    }

    @Test
    fun `little-endian фиксированной длины дополняет нулями`() {
        val bytes = keys.toLittleEndianFixed(BigInteger.valueOf(0x0102), 4)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00, 0x00), bytes)
    }
}
