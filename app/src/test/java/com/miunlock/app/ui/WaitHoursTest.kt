package com.miunlock.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Срок ожидания из текста ответа Xiaomi: его надо показать часами, а не простынёй. */
class WaitHoursTest {

    @Test
    fun `английский текст сервера`() {
        assertEquals(71, waitHoursIn("You must wait 71 hours before unlocking this device."))
    }

    @Test
    fun `короткая форма hour`() {
        assertEquals(24, waitHoursIn("Try again in 24 hour"))
    }

    @Test
    fun `русский текст`() {
        assertEquals(168, waitHoursIn("Попробуйте через 168 часов"))
    }

    @Test
    fun `регистр не важен`() {
        assertEquals(5, waitHoursIn("WAIT 5 HOURS"))
    }

    @Test
    fun `без срока возвращает null`() {
        assertNull(waitHoursIn("Invalid nonce param"))
        assertNull(waitHoursIn(""))
    }

    @Test
    fun `не путает часы с минутами и днями`() {
        assertNull(waitHoursIn("Wait 30 minutes"))
        assertNull(waitHoursIn("Wait 3 days"))
    }

    @Test
    fun `слишком большое число не принимается`() {
        assertNull(waitHoursIn("Wait 99999 hours"))
    }
}
