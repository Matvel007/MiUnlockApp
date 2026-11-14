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
    fun testParseStateKeepsRawFields() {
        // Поля сохраняются как есть: по ним разбираются чужие статусы,
        // и в журнал они попадают без догадок.
        val json = JSONObject("""{"code": 0, "data": {"is_pass": 1, "button_state": 1, "deadline_format": "2027-08-06 00:00"}}""")
        val result = api.parseState(json)
        assertEquals(1, result.isPass)
        assertEquals(1, result.buttonState)
        assertEquals("2027-08-06 00:00", result.deadline)
    }

    @Test
    fun testParseStateNotAppliedKeepsRawFields() {
        // «Можно подать заявку» — это is_pass=4/button_state=1, а не разрешение.
        val json = JSONObject("""{"code": 0, "data": {"is_pass": 4, "button_state": 1}}""")
        val result = api.parseState(json)
        assertEquals(2, result.code)
        assertEquals(4, result.isPass)
        assertEquals(1, result.buttonState)
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

    /** 100003 — не отказ: Xiaomi не подтвердил и не отказал, правду даст статус. */
    @Test
    fun testParseApplyMaybeApplied() {
        val json = JSONObject("""{"code": 100003, "message": "system busy"}""")
        val result = api.parseApply(json)
        assertEquals(7, result.code)
        assertFalse(result.successful)
        assertTrue(result.needsStateCheck)
        assertTrue(result.message.contains("не подтвердил"))
    }

    /** Незнакомый apply_result нельзя называть отказом — только «проверяю статус». */
    @Test
    fun testParseApplyUnknownResultAsksForState() {
        val json = JSONObject("""{"code": 0, "data": {"apply_result": 9}}""")
        val result = api.parseApply(json)
        assertEquals(6, result.code)
        assertFalse(result.successful)
        assertTrue(result.needsStateCheck)
        assertTrue(result.message.contains("apply_result=9"))
    }

    /** Неизвестный код: текст берём из `message`, если `msg` нет. */
    @Test
    fun testParseApplyErrorUsesMessageField() {
        val json = JSONObject("""{"code": 100500, "message": "system busy"}""")
        val result = api.parseApply(json)
        assertEquals(-1, result.code)
        assertFalse(result.successful)
        assertTrue(result.message.contains("system busy"))
    }

    @Test
    fun testParseStateBlockedAccount() {
        val json = JSONObject("""
            {"code": 0, "data": {"is_pass": 4, "button_state": 2, "deadline_format": "2026-12-01 00:00"}}
        """.trimIndent())
        val result = api.parseState(json)
        assertEquals(3, result.code)
        assertTrue(result.message.contains("2026-12-01"))
    }

    @Test
    fun testParseStateNewAccount() {
        val json = JSONObject("""{"code": 0, "data": {"is_pass": 4, "button_state": 3}}""")
        val result = api.parseState(json)
        assertEquals(4, result.code)
        assertTrue(result.message.contains("менее 30 дней"))
    }

    /** Незнакомое сочетание — это не «аккаунт молодой», а именно незнакомый статус. */
    @Test
    fun testParseStateUnknownCombination() {
        val json = JSONObject("""{"code": 0, "data": {"is_pass": 7, "button_state": 9}}""")
        val result = api.parseState(json)
        assertEquals(5, result.code)
        assertTrue(result.message.contains("is_pass=7"))
        assertTrue(result.message.contains("button_state=9"))
    }
}
