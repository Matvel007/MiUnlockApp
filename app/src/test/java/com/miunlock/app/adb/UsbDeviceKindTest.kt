package com.miunlock.app.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режим устройства определяется только протоколом интерфейса — без таблиц
 * моделей, чтобы новые телефоны работали сразу.
 */
class UsbDeviceKindTest {

    @Test
    fun `протокол 01 это ADB`() {
        assertEquals(UsbDeviceKind.Kind.ADB, UsbDeviceKind.of(0x01))
        assertTrue(UsbDeviceKind.canBeAdb(0x01))
        assertFalse(UsbDeviceKind.canBeFastboot(0x01))
    }

    @Test
    fun `протокол 03 это fastboot`() {
        assertEquals(UsbDeviceKind.Kind.FASTBOOT, UsbDeviceKind.of(0x03))
        assertTrue(UsbDeviceKind.canBeFastboot(0x03))
        assertFalse(UsbDeviceKind.canBeAdb(0x03))
    }

    @Test
    fun `неизвестный протокол подходит для обеих попыток`() {
        assertEquals(UsbDeviceKind.Kind.UNKNOWN, UsbDeviceKind.of(0x00))
        assertTrue(UsbDeviceKind.canBeAdb(0x00))
        assertTrue(UsbDeviceKind.canBeFastboot(0x00))
    }

    @Test
    fun `любой протокол кроме 03 годится для ADB`() {
        assertTrue(UsbDeviceKind.canBeAdb(0x00))
        assertTrue(UsbDeviceKind.canBeAdb(0x02))
        assertTrue(UsbDeviceKind.canBeAdb(0xFE))
    }

    @Test
    fun `любой протокол кроме 01 годится для fastboot`() {
        assertTrue(UsbDeviceKind.canBeFastboot(0x00))
        assertTrue(UsbDeviceKind.canBeFastboot(0x02))
        assertTrue(UsbDeviceKind.canBeFastboot(0xFE))
    }
}
