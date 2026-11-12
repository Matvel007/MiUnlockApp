package com.miunlock.app.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Различение ADB и fastboot. На живом телефоне ошибка здесь выглядела так:
 * телефон уже в загрузчике, а приложение шлёт ADB-пакет и получает «не отвечает».
 */
class UsbDeviceKindTest {

    @Test
    fun `fastboot по протоколу`() {
        assertTrue(UsbDeviceKind.isFastboot(0x03, 0x2717, 0x1234))
    }

    @Test
    fun `fastboot по известному PID даже с протоколом ADB`() {
        // Именно этот случай пришёл с Poco F3: 18D1:4EE7, протокол 0x01.
        assertTrue(UsbDeviceKind.isFastboot(0x01, 0x18D1, 0x4EE7))
        assertFalse(UsbDeviceKind.isAdb(0x01, 0x18D1, 0x4EE7))
    }

    @Test
    fun `обычный ADB распознаётся как ADB`() {
        assertTrue(UsbDeviceKind.isAdb(0x01, 0x2717, 0x2717))
        assertFalse(UsbDeviceKind.isFastboot(0x01, 0x2717, 0x2717))
    }

    @Test
    fun `незнакомый PID с протоколом fastboot`() {
        assertTrue(UsbDeviceKind.isFastboot(0x03, 0x1234, 0x5678))
    }

    @Test
    fun `незнакомый PID с протоколом ноль не считается ни тем ни другим`() {
        assertFalse(UsbDeviceKind.isAdb(0x00, 0x1234, 0x5678))
        assertFalse(UsbDeviceKind.isFastboot(0x00, 0x1234, 0x5678))
    }

    @Test
    fun `известный fastboot PID у неизвестного вендора не считается fastboot`() {
        assertFalse(UsbDeviceKind.isFastboot(0x01, 0x9999, 0x4EE7))
    }
}
