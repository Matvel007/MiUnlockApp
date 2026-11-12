package com.miunlock.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbPacketTest {

    private fun encode(command: Int, data: ByteArray = ByteArray(0)): ByteArray =
        AdbPacket.encode(command, 1, 0, data)

    @Test
    fun `encode и decode сохраняют поля`() {
        val payload = AdbPacket.payload("host::")
        val bytes = AdbPacket.encode(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, payload)
        val packet = AdbPacket.decode(bytes)!!
        assertEquals(AdbPacket.CNXN, packet.command)
        assertEquals(AdbPacket.VERSION, packet.arg0)
        assertEquals(AdbPacket.MAX_DATA, packet.arg1)
        assertArrayEquals(payload, packet.data)
        assertTrue(packet.isValid)
    }

    @Test
    fun `пустое тело разбирается`() {
        val packet = AdbPacket.decode(encode(AdbPacket.OKAY))!!
        assertEquals(AdbPacket.OKAY, packet.command)
        assertEquals(0, packet.data.size)
    }

    @Test
    fun `битый magic отбрасывается`() {
        val bytes = encode(AdbPacket.CNXN, AdbPacket.payload("host::"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        assertNull(AdbPacket.decode(bytes))
    }

    @Test
    fun `неверный crc отбрасывается`() {
        val bytes = encode(AdbPacket.CNXN, AdbPacket.payload("host::"))
        // crc лежит в заголовке перед magic
        val offset = AdbPacket.HEADER - 8
        bytes[offset] = (bytes[offset].toInt() xor 0xFF).toByte()
        assertNull(AdbPacket.decode(bytes))
    }

    @Test
    fun `обрезанное тело не считается пакетом`() {
        val bytes = encode(AdbPacket.CNXN, AdbPacket.payload("host::"))
        // на один байт меньше, чем заявлено в заголовке
        assertNull(AdbPacket.decode(bytes, bytes.size - 1))
    }

    @Test
    fun `отрицательная длина отбрасывается`() {
        val bytes = encode(AdbPacket.OKAY)
        ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-5)
        assertNull(AdbPacket.decode(bytes))
    }

    @Test
    fun `короткий заголовок отбрасывается`() {
        assertNull(AdbPacket.decode(ByteArray(AdbPacket.HEADER - 1)))
    }

    @Test
    fun `подделанный magic ловится валидацией`() {
        val bytes = encode(AdbPacket.CNXN, AdbPacket.payload("host::"))
        val packet = AdbPacket.decode(bytes)!!
        assertTrue(packet.isValid)
        val forged = packet.copy(magic = packet.magic xor 0x1)
        assertFalse(forged.isValid)
    }

    @Test
    fun `payload дописывает нулевой байт`() {
        val payload = AdbPacket.payload("reboot:bootloader")
        assertEquals("reboot:bootloader".length + 1, payload.size)
        assertEquals(0, payload.last().toInt())
    }

    @Test
    fun `payloadText снимает нули и пробелы`() {
        assertEquals("OKAY", AdbPacket.payloadText("OKAY\u0000".toByteArray(Charsets.US_ASCII)))
    }
}
