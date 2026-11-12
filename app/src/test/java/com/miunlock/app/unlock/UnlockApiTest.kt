package com.miunlock.app.unlock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockApiTest {

    @Test
    fun `зона выбирается по региону аккаунта`() {
        assertEquals(UnlockApi.ZONE_CHINA, UnlockApi.zoneForRegion("CN"))
        assertEquals(UnlockApi.ZONE_INDIA, UnlockApi.zoneForRegion("in"))
        assertEquals(UnlockApi.ZONE_RUSSIA, UnlockApi.zoneForRegion("RU"))
        assertEquals(UnlockApi.ZONE_EUROPE, UnlockApi.zoneForRegion("DE"))
        assertEquals(UnlockApi.ZONE_EUROPE, UnlockApi.zoneForRegion("gb"))
        assertEquals(UnlockApi.ZONE_SINGAPORE, UnlockApi.zoneForRegion("US"))
        assertEquals(UnlockApi.ZONE_SINGAPORE, UnlockApi.zoneForRegion(null))
        assertEquals(UnlockApi.ZONE_SINGAPORE, UnlockApi.zoneForRegion("  "))
    }

    @Test
    fun `домен соответствует зоне и неизвестная зона падает в Сингапур`() {
        assertEquals("https://unlock.update.miui.com", UnlockApi.domainForZone(UnlockApi.ZONE_CHINA))
        assertEquals("https://ru-unlock.update.intl.miui.com", UnlockApi.domainForZone(UnlockApi.ZONE_RUSSIA))
        assertEquals(
            "https://unlock.update.intl.miui.com",
            UnlockApi.domainForZone("Unknown zone"),
        )
    }

    @Test
    fun `cleanOrNot равный единице означает стирание данных`() {
        val api = UnlockApi()
        val wipe = api.parseClear(UnlockApiResult(0, JSONObject().put("cleanOrNot", 1).put("notice", "All data will be erased")))
        assertTrue(wipe.cleanOrNot)
        assertEquals("All data will be erased", wipe.notice)

        val keep = api.parseClear(UnlockApiResult(0, JSONObject().put("cleanOrNot", 0)))
        assertFalse(keep.cleanOrNot)
    }

    @Test
    fun `описание ошибки берётся из descEN`() {
        val result = UnlockApiResult(10005, JSONObject().put("descEN", "Invalid nonce param"))
        assertEquals("Invalid nonce param", result.desc)
        assertFalse(result.isOk)
    }

    @Test
    fun `без descEN описание падает на description и затем на код`() {
        assertEquals("bad", UnlockApiResult(1, JSONObject().put("description", "bad")).desc)
        assertEquals("code 7", UnlockApiResult(7, JSONObject()).desc)
    }

    @Test
    fun `код ноль считается успехом`() {
        assertTrue(UnlockApiResult(0, JSONObject()).isOk)
    }

    @Test
    fun `сессия считается валидной только при токене ssecurity и userId`() {
        val full = UnlockSession(serviceToken = "t", ssecurity = "s", userId = "1")
        assertTrue(full.isValid)
        assertFalse(UnlockSession(serviceToken = "t", ssecurity = "", userId = "1").isValid)
        assertFalse(UnlockSession(serviceToken = "t", ssecurity = "s", userId = "").isValid)
        assertFalse(UnlockSession().isValid)
    }

    @Test
    fun `cookieHeader собирается через точку с запятой`() {
        val session = UnlockSession(cookies = mapOf("userId" to "42", "serviceToken" to "abc"))
        val header = session.cookieHeader()
        assertTrue(header.contains("userId=42"))
        assertTrue(header.contains("serviceToken=abc"))
        assertTrue(header.contains("; "))
        assertEquals("", UnlockSession().cookieHeader())
    }
}
