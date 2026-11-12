package com.miunlock.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Поток ADB приходит кусками произвольного размера: полтора пакета,
 * два пакета разом, половина заголовка. Разбор обязан это пережить —
 * именно на этом ломался переход в fastboot на живом телефоне.
 */
class AdbFramerTest {

    private fun packet(command: Int, data: ByteArray = ByteArray(0)): ByteArray =
        AdbPacket.encode(command, 1, 0, data)

    @Test
    fun `целый пакет разбирается`() {
        val framer = AdbFramer()
        framer.feed(packet(AdbPacket.CNXN, AdbPacket.payload("device::")))
        val parsed = framer.next()
        assertNotNull(parsed)
        assertEquals(AdbPacket.CNXN, parsed!!.command)
        assertEquals(0, framer.buffered)
    }

    @Test
    fun `два пакета в одном куске`() {
        val framer = AdbFramer()
        framer.feed(packet(AdbPacket.OKAY) + packet(AdbPacket.CLSE))
        assertEquals(AdbPacket.OKAY, framer.next()!!.command)
        assertEquals(AdbPacket.CLSE, framer.next()!!.command)
        assertNull(framer.next())
    }

    @Test
    fun `пакет разорван на две части`() {
        val whole = packet(AdbPacket.AUTH, ByteArray(20) { it.toByte() })
        val framer = AdbFramer()
        framer.feed(whole.copyOfRange(0, 10))
        assertNull(framer.next())
        framer.feed(whole.copyOfRange(10, whole.size))
        val parsed = framer.next()
        assertEquals(AdbPacket.AUTH, parsed!!.command)
        assertEquals(20, parsed.data.size)
    }

    @Test
    fun `заголовок целиком, тело позже`() {
        val whole = packet(AdbPacket.WRTE, ByteArray(300) { 7 })
        val framer = AdbFramer()
        framer.feed(whole.copyOfRange(0, AdbPacket.HEADER))
        assertNull(framer.next())
        framer.feed(whole.copyOfRange(AdbPacket.HEADER, whole.size))
        assertEquals(300, framer.next()!!.data.size)
    }

    @Test
    fun `полтора пакета`() {
        val first = packet(AdbPacket.OKAY)
        val second = packet(AdbPacket.WRTE, ByteArray(100))
        val framer = AdbFramer()
        framer.feed(first + second.copyOfRange(0, 40))
        assertEquals(AdbPacket.OKAY, framer.next()!!.command)
        assertNull(framer.next())
        framer.feed(second.copyOfRange(40, second.size))
        assertEquals(100, framer.next()!!.data.size)
    }

    @Test
    fun `данные пакета не теряются при склейке`() {
        val payload = "reboot:bootloader\u0000".toByteArray(Charsets.US_ASCII)
        val framer = AdbFramer()
        framer.feed(packet(AdbPacket.OPEN, payload))
        assertArrayEquals(payload, framer.next()!!.data)
    }

    @Test
    fun `пустой кусок игнорируется`() {
        val framer = AdbFramer()
        framer.feed(ByteArray(0), 0)
        assertEquals(0, framer.buffered)
        assertNull(framer.next())
    }

    @Test
    fun `мусорный заголовок сбрасывает поток`() {
        val framer = AdbFramer()
        framer.feed(ByteArray(AdbPacket.HEADER) { 0xFF.toByte() })
        // длина тела выходит за предел — пакет не собираем и буфер чистим
        assertNull(framer.next())
        assertEquals(0, framer.buffered)
    }

    @Test
    fun `reset очищает накопленное`() {
        val framer = AdbFramer()
        framer.feed(packet(AdbPacket.OKAY))
        framer.reset()
        assertEquals(0, framer.buffered)
        assertNull(framer.next())
    }
}
