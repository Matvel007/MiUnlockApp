package com.miunlock.app.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Ключ ADB-хоста: им подписывается токен, который присылает целевой телефон.
 *
 * Формат публичной части — структура `RSAPublicKey` из `adb/crypto/rsa_2048.h`:
 * длина в словах, `n0inv`, модуль, `R^2 mod n`, экспонента, затем строка
 * ` user@host`. Всё в little-endian. Телефон сам считает по ней отпечаток
 * и показывает диалог «Разрешить отладку по USB?».
 */
class AdbKey(private val dir: File) {

    companion object {
        private const val FILE = "adbkey"
        private const val WORD = 4
        private const val MODULUS_BYTES = 256
        const val KEY_WORDS = 64
        /** 4 (len) + 4 (n0inv) + 256 (n) + 256 (rr) + 4 (exponent) */
        const val STRUCT_BYTES = 524

        /** DigestInfo для SHA-1: префикс PKCS#1 v1.5, как в ADB. */
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }

    private val privateFile = File(dir, FILE)
    private val publicFile = File(dir, "$FILE.pub")

    /** Загружает ключ, а при первом запуске создаёт новую пару RSA-2048. */
    fun loadOrCreate(): KeyPair {
        privateFile.parentFile?.mkdirs()
        if (privateFile.exists()) {
            runCatching { return readKeyPair() }
                .onFailure { privateFile.delete(); publicFile.delete() }
        }
        return generate()
    }

    private fun readKeyPair(): KeyPair {
        val encoded = privateFile.readBytes()
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateKey
        val publicKey = derivePublic(privateKey)
        return KeyPair(publicKey, privateKey)
    }

    private fun derivePublic(privateKey: RSAPrivateKey): RSAPublicKey {
        val exponent = (privateKey as? java.security.interfaces.RSAPrivateCrtKey)?.publicExponent
            ?: BigInteger.valueOf(65537)
        return KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(privateKey.modulus, exponent)) as RSAPublicKey
    }

    private fun generate(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        privateFile.writeBytes(pair.private.encoded)
        privateFile.setReadable(false, false)
        privateFile.setReadable(true, true)
        privateFile.setWritable(false, false)
        privateFile.setWritable(true, true)
        publicFile.writeText(encodePublic(pair.public as RSAPublicKey, "miunlockapp@android").toString(Charsets.US_ASCII))
        return pair
    }

    /** Подпись токена: RSA PKCS#1 v1.5 поверх SHA-1 DigestInfo. */
    fun signToken(token: ByteArray, privateKey: RSAPrivateKey): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1").digest(token)
        val block = SHA1_DIGEST_INFO + digest
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(block)
    }

    /** Публичный ключ в формате, который ждёт ADB. */
    fun encodePublic(key: RSAPublicKey, identity: String): ByteArray {
        val modulus = toLittleEndianFixed(key.modulus, MODULUS_BYTES)
        val n0inv = computeN0inv(key.modulus)
        val rr = key.modulus.let { n ->
            BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8 * 2).mod(n)
        }
        val rrBytes = toLittleEndianFixed(rr, MODULUS_BYTES)

        val out = java.io.ByteArrayOutputStream()
        out.write(intLe(KEY_WORDS))
        out.write(intLe(n0inv))
        out.write(modulus)
        out.write(rrBytes)
        out.write(intLe(key.publicExponent.toInt()))
        out.write(" $identity".toByteArray(Charsets.US_ASCII))
        out.write(0)
        return out.toByteArray()
    }

    /** `-1 / n[0] mod 2^32`, как в adb. */
    fun computeN0inv(modulus: BigInteger): Int {
        val mask = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.and(mask.subtract(BigInteger.ONE))
        val inv = n0.modInverse(mask)
        return mask.subtract(inv).and(mask.subtract(BigInteger.ONE)).toInt()
    }

    fun toLittleEndianFixed(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val trimmed = if (be.size > size) be.copyOfRange(be.size - size, be.size) else be
        val out = ByteArray(size)
        for (i in trimmed.indices) {
            out[i] = trimmed[trimmed.size - 1 - i]
        }
        return out
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )
}
