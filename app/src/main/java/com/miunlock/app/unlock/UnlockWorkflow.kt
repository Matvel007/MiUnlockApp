package com.miunlock.app.unlock

import android.hardware.usb.UsbDevice
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
     * Токен устройства. Сначала `oem get_token`, если не вышло — `getvar:token`.
     * Часть загрузчиков отдаёт `GetVar Variable Not found` до нескольких попыток.
     */
    fun token(attempts: Int = 3): Result<String> {
        repeat(attempts) {
            val oem = usb.execute("oem get_token").getOrNull()
            val fromOem = oem?.let { FastbootCodec.mergeToken(it.infoLines, it.payload) }.orEmpty()
            if (fromOem.isNotEmpty()) return Result.success(fromOem)

            val getvar = usb.execute("getvar:token").getOrNull()
            val fromGetvar = getvar?.let { FastbootCodec.mergeToken(it.infoLines, it.payload) }.orEmpty()
            if (fromGetvar.isNotEmpty()) return Result.success(fromGetvar)
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
        fun step(s: UnlockStep, ru: String, en: String) = listener.onProgress(UnlockProgress(s, ru, en))

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

            step(UnlockStep.NONCE, "Запрашиваю nonce", "Requesting a nonce")
            val nonce1 = api.nonce(domain, session.ssecurity, session.cookies)
            if (!nonce1.isOk) {
                return UnlockOutcome.Failure(UnlockStep.NONCE, nonce1.desc, nonce1.code)
            }
            val nonce = nonce1.json.optString("nonce")
            if (nonce.isEmpty()) {
                return UnlockOutcome.Failure(UnlockStep.NONCE, "сервер вернул пустой nonce")
            }

            step(UnlockStep.CLEAR, "Проверяю, будут ли стёрты данные", "Checking whether data will be wiped")
            val clear = api.deviceClear(domain, product, nonce, session.ssecurity, session.cookies)
            if (!clear.isOk) {
                return UnlockOutcome.Failure(UnlockStep.CLEAR, clear.desc, clear.code)
            }
            val info = api.parseClear(clear)
            listener.onProgress(UnlockProgress(UnlockStep.CLEAR, info.notice.ifEmpty { "проверка пройдена" }, info.notice.ifEmpty { "check passed" }))
            if (!onConfirmClear(info.cleanOrNot, info.notice)) {
                return UnlockOutcome.Cancelled
            }

            // nonce израсходован вызовом device/clear — нужен новый, иначе сервер
            // ответит 10005 Invalid nonce param.
            step(UnlockStep.NONCE, "Запрашиваю новый nonce", "Requesting a fresh nonce")
            val nonce2res = api.nonce(domain, session.ssecurity, session.cookies)
            if (!nonce2res.isOk) {
                return UnlockOutcome.Failure(UnlockStep.NONCE, nonce2res.desc, nonce2res.code)
            }
            val nonce2 = nonce2res.json.optString("nonce")

            step(UnlockStep.AHA, "Запрашиваю подпись у Xiaomi", "Requesting the Xiaomi signature")
            val aha = api.ahaUnlock(
                domain = domain,
                product = product,
                deviceToken = token,
                nonce = nonce2,
                ssecurity = session.ssecurity,
                deviceId = session.deviceId,
                userId = session.userId,
                cookies = session.cookies,
            )
            if (!aha.isOk) {
                return UnlockOutcome.Failure(UnlockStep.AHA, aha.desc, aha.code)
            }
            val encryptData = aha.json.optString("encryptData")
            if (encryptData.isEmpty()) {
                return UnlockOutcome.Failure(UnlockStep.AHA, "сервер вернул пустой encryptData")
            }

            step(UnlockStep.STAGE, "Передаю подпись в загрузчик", "Uploading the signature to the bootloader")
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
