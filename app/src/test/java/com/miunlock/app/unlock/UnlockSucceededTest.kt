package com.miunlock.app.unlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * После `oem unlock` телефон стирает данные и уходит в перезагрузку, поэтому
 * ответ загрузчика может не дойти. Проверяем правило: молчание + уход с шины —
 * успех, всё остальное — нет.
 */
class UnlockSucceededTest {

    @Test
    fun `ответ OKAY — успех`() {
        assertTrue(unlockSucceeded(reply = true, stillConnected = true))
        assertTrue(unlockSucceeded(reply = true, stillConnected = false))
    }

    @Test
    fun `молчание и устройство ушло с шины — успех`() {
        assertTrue(unlockSucceeded(reply = false, stillConnected = false))
    }

    @Test
    fun `молчание, но устройство на связи — ошибка`() {
        // Загрузчик жив и не ответил: это настоящий сбой, а не перезагрузка.
        assertFalse(unlockSucceeded(reply = false, stillConnected = true))
    }
}
