package com.miunlock.app.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Пакет протокола ADB. Заголовок — 24 байта little-endian:
 * `command, arg0, arg1, data_length, data_crc32, magic`, где `magic = command xor 0xFFFFFFFF`.
 */
data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray,
) {
    val isValid: Boolean get() = command == (magic.inv())

    private val magic: Int
        get() = command xor 0xFFFFFFFF.toInt()

    companion object {
        const val HEADER = 24

        const val SYNC = 0x434E5953
        const val CNXN = 0x4E584E43
        const val OPEN = 0x4E45504F
        const val OKAY = 0x59414B4F
        const val CLSE = 0x434C5345
        const val WRTE = 0x45545257
        const val AUTH = 0x48545541

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        const val VERSION = 0x01000000
        const val MAX_DATA = 4096

        fun crc32(data: ByteArray): Int {
            val crc = CRC32()
            crc.update(data)
            return crc.value.toInt()
        }

        fun encode(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(command)
            buffer.putInt(arg0)
            buffer.putInt(arg1)
            buffer.putInt(data.size)
            buffer.putInt(crc32(data))
            buffer.putInt(command xor 0xFFFFFFFF.toInt())
            buffer.put(data)
            return buffer.array()
        }

        fun decode(bytes: ByteArray, length: Int = bytes.size): AdbPacket? {
            if (length < HEADER) return null
            val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val size = buffer.int
            val checksum = buffer.int
            val magic = buffer.int
            if (magic != (command xor 0xFFFFFFFF.toInt())) return null
            val available = length - HEADER
            if (size < 0 || size > available) {
                // Тело могло не прийти целиком: отдаём то, что есть, чтобы не терять пакет.
                val partial = ByteArray(available).also { buffer.get(it) }
                return AdbPacket(command, arg0, arg1, partial)
            }
            val data = ByteArray(size).also { buffer.get(it) }
            if (checksum != crc32(data) && data.isNotEmpty()) return null
            return AdbPacket(command, arg0, arg1, data)
        }

        fun payload(text: String): ByteArray =
            ByteArrayOutputStream().apply {
                write(text.toByteArray(Charsets.US_ASCII))
                write(0)
            }.toByteArray()

        fun payloadText(data: ByteArray): String =
            String(data, Charsets.US_ASCII).trimEnd('\u0000').trim()
    }
}
