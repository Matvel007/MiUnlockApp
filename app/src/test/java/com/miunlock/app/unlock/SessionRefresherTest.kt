package com.miunlock.app.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Слияние сессии с ответом обновления. Пустые поля ответа не должны затирать
 * сохранённые: без ssecurity или домена сессия перестанет работать.
 */
class SessionRefresherTest {

    private val session = UnlockSession(
        serviceToken = "old-token",
        ssecurity = "old-ssecurity",
        userId = "1000000001",
        deviceId = "wb_1",
        cookies = mapOf("passToken" to "p", "userId" to "1000000001"),
        zone = "Europe",
        sid = "unlockApi",
        stsDomain = "https://unlock.update.miui.com",
    )

    @Test
    fun `пустые cookies оставляют сессию как была`() {
        val merged = mergeRefreshed(session, emptyMap(), "new", "new", "https://x")
        assertSame(session, merged)
    }

    @Test
    fun `новые cookies заменяют старые`() {
        val fresh = mapOf("passToken" to "p", "serviceToken" to "fresh")
        val merged = mergeRefreshed(session, fresh, "s", "t", "")
        assertEquals(fresh, merged.cookies)
    }

    @Test
    fun `пустые поля ответа не затирают сохранённые`() {
        val merged = mergeRefreshed(session, mapOf("a" to "b"), "", "", "")
        assertEquals("old-ssecurity", merged.ssecurity)
        assertEquals("old-token", merged.serviceToken)
        assertEquals("https://unlock.update.miui.com", merged.stsDomain)
    }

    @Test
    fun `заполненные поля ответа заменяют сохранённые`() {
        val merged = mergeRefreshed(
            session,
            mapOf("a" to "b"),
            ssecurity = "new-ssecurity",
            token = "new-token",
            stsDomain = "https://eu-unlock.update.intl.miui.com",
        )
        assertEquals("new-ssecurity", merged.ssecurity)
        assertEquals("new-token", merged.serviceToken)
        assertEquals("https://eu-unlock.update.intl.miui.com", merged.stsDomain)
    }

    @Test
    fun `обновление не трогает аккаунт и зону`() {
        val merged = mergeRefreshed(session, mapOf("a" to "b"), "s", "t", "https://x")
        assertEquals("1000000001", merged.userId)
        assertEquals("wb_1", merged.deviceId)
        assertEquals("Europe", merged.zone)
        assertEquals("unlockApi", merged.sid)
    }
}
