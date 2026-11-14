package com.miunlock.app.unlock

import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.domain.StateResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок источников: сначала unlock-сервер по токену (он же называет часы),
 * затем Mi Community. Проверки идут без сети — источники подменены.
 */
class AccountStatusCheckerTest {

    private val credentials = Credentials(serviceToken = "t", deviceId = "d")
    private val proxy = ProxySettings()
    private val session = UnlockSession(
        sid = "unlockApi",
        userId = "111",
        deviceId = "dev",
        ssecurity = "c2VjcmV0MTIzNDU2Nzg5MA==",
        cookies = mapOf("userId" to "111"),
    )
    private val saved = SavedDeviceToken(product = "dada", token = "TOKEN", uid = "111")

    private fun granted() = AccountStatus(
        granted = true,
        source = "bbs",
        message = "bbs: есть",
    )

    private fun checker(
        bbs: suspend () -> StateResult = { StateResult(code = 2, message = "bbs: можно подавать") },
        token: suspend () -> UnlockOutcome = { UnlockOutcome.Failure(UnlockStep.AHA, "нет", 10000) },
    ) = AccountStatusChecker(
        bbsState = { _, _ -> bbs() },
        byToken = { _, _ -> token() },
    )

    @Test
    fun `успех по токену даёт разрешение без запроса в bbs`() = runBlocking {
        var bbsCalled = false
        val result = checker(
            bbs = { bbsCalled = true; StateResult(code = 2, message = "x") },
            token = { UnlockOutcome.Success("ok") },
        ).check(credentials, proxy, session, saved)
        assertTrue(result.granted)
        assertEquals("token", result.source)
        assertFalse("bbs не должен опрашиваться", bbsCalled)
    }

    @Test
    fun `часы по токену означают разрешение`() = runBlocking {
        val result = checker(
            token = { UnlockOutcome.Failure(UnlockStep.AHA, "ждём", 20036, waitHours = 60) },
        ).check(credentials, proxy, session, saved)
        assertTrue(result.granted)
        assertEquals(60, result.waitHours)
        assertEquals("token", result.source)
    }

    @Test
    fun `непривязанное устройство не отменяет одобрение - решает bbs`() = runBlocking {
        val result = checker(
            bbs = { StateResult(code = 1, message = "есть", deadline = "2026-11-16") },
            token = { UnlockOutcome.Failure(UnlockStep.AHA, "нет привязки", 20031) },
        ).check(credentials, proxy, session, saved)
        assertTrue(result.granted)
        assertEquals("bbs", result.source)
        assertEquals("2026-11-16", result.deadline)
    }

    @Test
    fun `без токена решает bbs`() = runBlocking {
        val result = checker(bbs = { StateResult(code = 2, message = "можно") })
            .check(credentials, proxy, session, null)
        assertFalse(result.granted)
        assertTrue(result.canApply)
        assertEquals("bbs", result.source)
    }

    @Test
    fun `bbs с is_pass 4 при выданном разрешении не отменяет ответ токена`() = runBlocking {
        // Ровно случай тестера: bbs говорит «заявки нет», а разрешение выдано.
        val result = checker(
            bbs = { StateResult(code = 2, message = "можно", isPass = 4, buttonState = 1) },
            token = { UnlockOutcome.Failure(UnlockStep.AHA, "ждём", 20036, waitHours = 60) },
        ).check(credentials, proxy, session, saved)
        assertTrue(result.granted)
        assertEquals(60, result.waitHours)
    }

    @Test
    fun `ошибка сети по токену не выдаётся за отсутствие разрешения`() = runBlocking {
        val result = checker(
            bbs = { StateResult(code = 2, message = "можно подавать") },
            token = { UnlockOutcome.Failure(UnlockStep.NONCE, "таймаут", null) },
        ).check(credentials, proxy, session, saved)
        // Источник токена промолчал — итог даёт bbs, и он «можно подавать»,
        // а не «разрешения нет».
        assertFalse(result.granted)
        assertTrue(result.canApply)
        assertFalse(result.failed)
        assertNull(result.waitHours)
    }

    @Test
    fun `непонятный ответ bbs помечается как сбой а не как отказ`() = runBlocking {
        val result = checker(
            bbs = { StateResult(code = 0, message = "сервер занят") },
            token = { UnlockOutcome.Failure(UnlockStep.NONCE, "таймаут", null) },
        ).check(credentials, proxy, session, null)
        assertFalse(result.granted)
        assertFalse(result.canApply)
        assertTrue("это сбой проверки, а не «разрешения нет»", result.failed)
    }

    @Test
    fun `без данных аккаунта и без токена статус не выдумывается`() = runBlocking {
        val result = checker().check(Credentials(), proxy, session, null)
        assertFalse(result.granted)
        assertFalse(result.canApply)
        assertTrue("это отсутствие источника, а не отказ", result.failed)
        assertTrue(result.message.contains("Статус неизвестен"))
    }

    @Test
    fun `токен даёт статус без данных аккаунта`() = runBlocking {
        val result = checker(
            bbs = { error("bbs не должен вызываться без данных аккаунта") },
            token = { UnlockOutcome.Failure(UnlockStep.AHA, "ждём", 20036, waitHours = 40) },
        ).check(Credentials(), proxy, session, saved)
        assertTrue(result.granted)
        assertEquals(40, result.waitHours)
        assertEquals("token", result.source)
    }

    @Test
    fun `сбой сети говорит про интернет а не про отсутствие источника`() = runBlocking {
        val result = checker(
            bbs = { error("bbs не вызывается: данных аккаунта нет") },
            token = { UnlockOutcome.Failure(UnlockStep.AHA, "сервер не ответил ни в одной зоне", null) },
        ).check(Credentials(), proxy, session, saved)
        assertFalse(result.granted)
        assertTrue(result.failed)
        assertTrue(
            "должно говориться про связь, а не про отсутствие источника: ${result.message}",
            result.message.contains("Нет связи"),
        )
    }

    @Test
    fun `когда ожидание истекло - разрешение есть и никакого попробуйте позже`() = runBlocking {
        // Код 0: сервер подписал токен, часы больше не называются.
        val result = checker(
            bbs = { error("bbs не должен вызываться: ответил токен") },
            token = { UnlockOutcome.Success("Разрешение есть — можно разблокировать") },
        ).check(Credentials(), proxy, session, saved)
        assertTrue("разрешение должно быть", result.granted)
        assertFalse("это не сбой", result.failed)
        assertFalse("подавать заявку уже не нужно", result.canApply)
        assertNull("часов больше нет", result.waitHours)
        assertTrue(result.message.contains("РАЗРЕШЕНИЕ ЕСТЬ"))
        assertFalse("никакого «попробуйте позже»", result.message.contains("попробуйте", ignoreCase = true))
    }
}
