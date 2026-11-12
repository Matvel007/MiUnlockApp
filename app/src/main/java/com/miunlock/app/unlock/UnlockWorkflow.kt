package com.miunlock.app.unlock

import android.hardware.usb.UsbDevice
import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import com.miunlock.app.fastboot.FastbootCodec
import com.miunlock.app.fastboot.FastbootUsb

/** Шаги разблокировки, в порядке выполнения. */
enum class UnlockStep {
    DEVICE, PRODUCT, TOKEN, NONCE, CLEAR, AHA, STAGE, UNLOCK, DONE,
}

/** Сообщение шага сразу на двух языках: интерфейс выбирает нужный при отрисовке. */
data class UnlockProgress(
    val step: UnlockStep,
    val ru: String,
    val en: String,
)

/** Результат всей операции. */
sealed class UnlockOutcome {
    data class Success(val message: String) : UnlockOutcome()
    data class Failure(val step: UnlockStep, val message: String, val code: Int? = null) : UnlockOutcome()
    data object Cancelled : UnlockOutcome()
}

/** Команды fastboot, нужные для разблокировки. */
class FastbootCommands(private val usb: FastbootUsb) {

    fun product(): Result<String> {
        val res = usb.execute("getvar:product")
            .getOrElse { return Result.failure(it) }
        if (res.status != FastbootCodec.STATUS_OKAY) {
            return Result.failure(IllegalStateException("getvar:product: ${res.payload}"))
        }
        val name = FastbootCodec.parseProduct(res.payload, res.infoLines)
        return if (name.isEmpty()) {
            Result.failure(IllegalStateException("загрузчик не отдал codename"))
        } else {
            Result.success(name)
        }
    }

    /**
     * Токен устройства. Сначала `oem get_token`, если команда не поддерживается —
     * `getvar:token`. HyperOS отвечает на первую `FAILunknown command`, это норма.
     */
    fun token(): Result<String> {
        val oem = usb.execute("oem get_token").getOrNull()
        if (oem != null) {
            val fromOem = FastbootCodec.parseToken(oem.payload, oem.infoLines)
            if (fromOem.isNotEmpty()) {
                DebugLog.trace(LogTag.FASTBOOT, "токен получен через oem get_token")
                return Result.success(fromOem)
            }
            DebugLog.trace(LogTag.FASTBOOT, "oem get_token не дал токен: ${oem.status} ${oem.payload}")
        }

        val getvar = usb.execute("getvar:token").getOrNull()
        if (getvar != null) {
            val fromGetvar = FastbootCodec.parseToken(getvar.payload, getvar.infoLines)
            if (fromGetvar.isNotEmpty()) {
                DebugLog.trace(LogTag.FASTBOOT, "токен получен через getvar:token, ${fromGetvar.length} символов")
                return Result.success(fromGetvar)
            }
            DebugLog.warn(LogTag.FASTBOOT, "getvar:token не дал токен: ${getvar.status} ${getvar.payload}")
        }
        return Result.failure(IllegalStateException("устройство не отдало токен"))
    }

    /** Передаёт подпись в память загрузчика. */
    fun stage(data: ByteArray): Result<Unit> {
        val hex = FastbootCodec.hexLength(data.size)
        val res = usb.execute("stage:$hex").getOrElse { return Result.failure(it) }
        if (res.status != FastbootCodec.STATUS_DATA) {
            return Result.failure(IllegalStateException("stage отклонён: ${res.status} ${res.payload}"))
        }
        usb.sendData(data).getOrElse { return Result.failure(it) }
        val confirm = usb.readUntilFinal().getOrElse { return Result.failure(it) }
        return if (confirm.isOkay) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("stage не принят: ${confirm.payload}"))
        }
    }

    fun oemUnlock(): Result<String> {
        val res = usb.execute("oem unlock").getOrElse { return Result.failure(it) }
        return if (res.isOkay) {
            Result.success(res.payload.ifEmpty { "OKAY" })
        } else {
            Result.failure(IllegalStateException("oem unlock: ${res.payload}"))
        }
    }

    fun reboot(): Result<Unit> = usb.execute("reboot").map { }
}

/**
 * Вся последовательность разблокировки.
 *
 * Порядок жёсткий: nonce одноразовый и `device/clear` его съедает, поэтому перед
 * `ahaUnlock` запрашивается новый. deviceToken живёт до перезагрузки целевого
 * устройства, поэтому вся цепочка идёт за один сеанс.
 */
class UnlockWorkflow(
    private val usb: FastbootUsb,
    private val api: UnlockApi,
) {

    fun interface Listener {
        fun onProgress(progress: UnlockProgress)
    }

    /** Код сервера: nonce уже использован другим вызовом. */
    private companion object {
        const val CODE_INVALID_NONCE = 10005
    }

    /** Один вызов ahaUnlock с заданным nonce. */
    private suspend fun requestAha(
        domain: String,
        product: String,
        token: String,
        nonce: String,
        session: UnlockSession,
    ): UnlockApiResult = api.ahaUnlock(
        domain = domain,
        product = product,
        deviceToken = token,
        nonce = nonce,
        ssecurity = session.ssecurity,
        deviceId = session.deviceId,
        userId = session.userId,
        cookies = session.cookies,
    )

    /**
     * @param session авторизованная сессия разблокировки
     * @param device  уже выбранное USB-устройство (разрешение должно быть получено)
     * @param onConfirmClear вызывается перед необратимым шагом; false — отмена
     */
    suspend fun run(
        session: UnlockSession,
        device: UsbDevice,
        listener: Listener,
        onConfirmClear: suspend (cleanOrNot: Boolean, notice: String) -> Boolean = { _, _ -> true },
    ): UnlockOutcome {
        fun step(s: UnlockStep, ru: String, en: String) {
            DebugLog.add(LogLevel.INFO, LogTag.APP, "шаг $s: $ru")
            listener.onProgress(UnlockProgress(s, ru, en))
        }

        step(UnlockStep.DEVICE, "Открываю USB-устройство", "Opening the USB device")
        usb.open(device).getOrElse {
            return UnlockOutcome.Failure(UnlockStep.DEVICE, it.message ?: "не удалось открыть устройство")
        }
        try {
            step(UnlockStep.PRODUCT, "Читаю codename устройства", "Reading the device codename")
            val product = FastbootCommands(usb).product().getOrElse {
                return UnlockOutcome.Failure(UnlockStep.PRODUCT, it.message ?: "нет codename")
            }
            listener.onProgress(UnlockProgress(UnlockStep.PRODUCT, "Устройство: $product", "Device: $product"))

            step(UnlockStep.TOKEN, "Читаю токен устройства", "Reading the device token")
            val token = FastbootCommands(usb).token().getOrElse {
                return UnlockOutcome.Failure(UnlockStep.TOKEN, it.message ?: "нет токена")
            }
            listener.onProgress(UnlockProgress(UnlockStep.TOKEN, "Токен получен, ${token.length} символов", "Token received, ${token.length} chars"))

            val domain = UnlockApi.domainForZone(session.zone)
            DebugLog.add(
                LogLevel.INFO,
                LogTag.API,
                "зона ${session.zone}, домен $domain",
            )

            step(UnlockStep.NONCE, "Запрашиваю nonce", "Requesting a nonce")
            val nonce1 = api.nonce(domain, session.ssecurity, session.cookies)
            DebugLog.trace(LogTag.API, "nonce: код ${nonce1.code} ${nonce1.desc}")
            if (!nonce1.isOk) {
                return UnlockOutcome.Failure(UnlockStep.NONCE, nonce1.desc, nonce1.code)
            }
            val nonce = nonce1.json.optString("nonce")
            if (nonce.isEmpty()) {
                return UnlockOutcome.Failure(UnlockStep.NONCE, "сервер вернул пустой nonce")
            }

            step(UnlockStep.CLEAR, "Проверяю, будут ли стёрты данные", "Checking whether data will be wiped")
            val clear = api.deviceClear(domain, product, nonce, session.ssecurity, session.cookies)
            DebugLog.trace(LogTag.API, "device/clear: код ${clear.code} ${clear.desc}")
            if (!clear.isOk) {
                // Отказ на этом шаге не приговор: сервер отвечает так и на уже
                // разблокированном устройстве, и на неподходящей зоне. Идём
                // дальше — ответ ahaUnlock скажет точнее.
                DebugLog.warn(LogTag.API, "device/clear отказал: ${clear.code} ${clear.desc}")
            }
            val info = api.parseClear(clear)
            listener.onProgress(UnlockProgress(UnlockStep.CLEAR, info.notice.ifEmpty { "проверка пройдена" }, info.notice.ifEmpty { "check passed" }))
            if (!onConfirmClear(info.cleanOrNot, info.notice)) {
                return UnlockOutcome.Cancelled
            }

            // Проверенный порядок: тот же nonce идёт в ahaUnlock. Если сервер
            // всё же скажет 10005 (nonce израсходован), берём новый и повторяем
            // один раз — так покрыты обе реализации.
            step(UnlockStep.AHA, "Запрашиваю подпись у Xiaomi", "Requesting the Xiaomi signature")
            var aha = requestAha(domain, product, token, nonce, session)
            DebugLog.trace(LogTag.API, "ahaUnlock: код ${aha.code} ${aha.desc}")
            if (!aha.isOk && aha.code == CODE_INVALID_NONCE) {
                DebugLog.warn(LogTag.API, "ahaUnlock: nonce израсходован, беру новый")
                val fresh = api.nonce(domain, session.ssecurity, session.cookies)
                if (!fresh.isOk) {
                    return UnlockOutcome.Failure(UnlockStep.NONCE, fresh.desc, fresh.code)
                }
                val nonce2 = fresh.json.optString("nonce")
                aha = requestAha(domain, product, token, nonce2, session)
                DebugLog.trace(LogTag.API, "ahaUnlock (повтор): код ${aha.code} ${aha.desc}")
            }
            if (!aha.isOk) {
                return UnlockOutcome.Failure(UnlockStep.AHA, aha.desc, aha.code)
            }
            val encryptData = aha.json.optString("encryptData")
            if (encryptData.isEmpty()) {
                return UnlockOutcome.Failure(UnlockStep.AHA, "сервер вернул пустой encryptData")
            }

            step(UnlockStep.STAGE, "Передаю подпись в загрузчик", "Uploading the signature to the bootloader")
            DebugLog.trace(LogTag.APP, "encryptData: ${encryptData.length} символов hex")
            val bytes = runCatching { UnlockCrypto.hexToBytes(encryptData) }.getOrElse {
                return UnlockOutcome.Failure(UnlockStep.STAGE, "encryptData не hex")
            }
            FastbootCommands(usb).stage(bytes).getOrElse {
                return UnlockOutcome.Failure(UnlockStep.STAGE, it.message ?: "stage отклонён")
            }

            step(UnlockStep.UNLOCK, "Разблокирую", "Unlocking")
            val unlock = FastbootCommands(usb).oemUnlock().getOrElse {
                return UnlockOutcome.Failure(UnlockStep.UNLOCK, it.message ?: "oem unlock отклонён")
            }

            step(UnlockStep.DONE, "Готово", "Done")
            return UnlockOutcome.Success(unlock)
        } finally {
            usb.close()
        }
    }
}
