package com.miunlock.app.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подсказки по кодам ответа unlock.update.* — их видит человек вместо сухого кода. */
class UnlockHintTest {

    @Test
    fun `лимит аккаунта объясняется словами`() {
        // 10023 и 20030 — одно и то же правило Xiaomi, встречаются оба.
        val hint = unlockHint(10023)
        assertNotNull(hint)
        assertTrue(hint!!.ru.contains("в этом месяце"))
        assertTrue(hint.en.contains("this month"))
        assertNotNull(unlockHint(20030))
    }

    @Test
    fun `неизрасходованный nonce просит повторить`() {
        val hint = unlockHint(10005)
        assertNotNull(hint)
        assertTrue(hint!!.ru.contains("ещё раз"))
    }

    @Test
    fun `неизвестный код не выдумывает объяснение`() {
        assertNull(unlockHint(99999))
        assertNull(unlockHint(null))
    }
}
