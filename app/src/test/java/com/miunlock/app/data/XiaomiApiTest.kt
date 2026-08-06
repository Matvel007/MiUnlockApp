package com.miunlock.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiApiTest {
    private val api = XiaomiApi()

    @Test
    fun testParseStateIsPass() {
        val json = JSONObject("""
            {
                "code": 0,
                "data": {
                    "is_pass": 1,
                    "button_state": 1,
                    "deadline_format": "2027-08-06 00:00"
                }
            }
        """.trimIndent())
        val result = api.parseState(json)
        assertEquals(1, result.code)
        assertTrue(result.message.contains("уже получен"))
    }

    @Test
    fun testParseApplyQuotaReached() {
        val json = JSONObject("""
            {
                "code": 0,
                "data": {
                    "apply_result": 3,
                    "deadline_format": "2026-08-07 00:00"
                },
                "ts": 1722979200
            }
        """.trimIndent())
        val result = api.parseApply(json)
        assertEquals(3, result.code)
        assertFalse(result.successful)
        assertTrue(result.message.contains("Лимит заявок исчерпан"))
    }

    @Test
    fun testParseApplySuccess() {
        val json = JSONObject("""
            {
                "code": 0,
                "data": {
                    "apply_result": 1
                },
                "ts": 1722979200
            }
        """.trimIndent())
        val result = api.parseApply(json)
        assertEquals(1, result.code)
        assertTrue(result.successful)
        assertEquals("Заявка успешно подана", result.message)
    }
}
