package com.miunlock.app.data

import com.miunlock.app.domain.Credentials
import com.miunlock.app.domain.ProxySettings
import com.miunlock.app.unlock.SavedDeviceToken
import com.miunlock.app.unlock.UnlockSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Журнал копируют и пересылают. Значения токенов и cookie в него попадать
 * не должны — только имена и длины.
 */
class AuthDumpTest {

    private val session = UnlockSession(
        serviceToken = "SECRET_SERVICE_TOKEN",
        ssecurity = "SECRET_SSECURITY",
        userId = "1000000001",
        deviceId = "wb_test",
        cookies = mapOf(
            "passToken" to "SECRET_PASS_TOKEN",
            "userId" to "1000000001",
        ),
        zone = "Europe",
        sid = "unlockApi",
        stsDomain = "https://unlock.update.miui.com",
    )

    private val credentials = Credentials(
        serviceToken = "SECRET_APPLY_TOKEN",
        deviceId = "wb_apply",
        userId = "1000000001",
    )

    @Test
    fun `в дампе разблокировки нет значений секретов`() {
        val dump = AuthDump.forUnlock(session, SavedDeviceToken("testdevice", "DEVICE_TOKEN_OK", "1000000001"), ProxySettings())
        assertFalse("serviceToken утёк", dump.contains("SECRET_SERVICE_TOKEN"))
        assertFalse("ssecurity утёк", dump.contains("SECRET_SSECURITY"))
        assertFalse("passToken утёк", dump.contains("SECRET_PASS_TOKEN"))
    }

    @Test
    fun `в дампе подачи нет значения токена`() {
        val dump = AuthDump.forApply(credentials, ProxySettings())
        assertFalse("токен утёк", dump.contains("SECRET_APPLY_TOKEN"))
    }

    @Test
    fun `структура сессии всё равно видна`() {
        val dump = AuthDump.forUnlock(session, SavedDeviceToken("testdevice", "DEVICE_TOKEN_OK", "1000000001"), ProxySettings())
        // По этим строкам видно, полная ли сессия и тот ли аккаунт.
        assertTrue(dump.contains("unlock.sid=unlockApi"))
        assertTrue(dump.contains("unlock.userId=1000000001"))
        assertTrue(dump.contains("passToken:17"))
        assertTrue(dump.contains("unlock.ssecurity=16 симв."))
    }

    @Test
    fun `токен устройства остаётся`() {
        // Это не секрет аккаунта: без него статус не спросить, а по логу
        // видно, что токен прочитан.
        val dump = AuthDump.forUnlock(session, SavedDeviceToken("testdevice", "DEVICE_TOKEN_OK", "1000000001"), ProxySettings())
        assertTrue(dump.contains("device.token=DEVICE_TOKEN_OK"))
        assertTrue(dump.contains("device.product=testdevice"))
    }

    @Test
    fun `пустые значения помечаются явно`() {
        val dump = AuthDump.forApply(Credentials(), ProxySettings())
        assertTrue(dump.contains("apply.token=нет"))
    }
}
