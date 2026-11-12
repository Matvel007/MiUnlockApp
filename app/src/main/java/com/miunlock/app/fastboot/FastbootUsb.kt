package com.miunlock.app.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.nio.charset.StandardCharsets

/**
 * USB-транспорт fastboot: телефон работает как ПК.
 *
 * Root не нужен: система даёт приложению файловый дескриптор устройства через
 * [UsbManager] после подтверждения пользователем.
 */
class FastbootUsb(private val context: Context) {

    companion object {
        const val ACTION_PERMISSION = "com.miunlock.app.FASTBOOT_USB_PERMISSION"

        /** Стандартный интерфейс fastboot по AOSP. */
        const val CLASS = 0xFF
        const val SUBCLASS = 0x42
        const val PROTOCOL = 0x03


        const val TIMEOUT_CMD = 5_000
        const val TIMEOUT_DATA = 30_000
        const val BUFFER = 4096
        const val CHUNK = 16_384
    }

    private val usb: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null

    val isOpen: Boolean get() = connection != null && epIn != null && epOut != null
    val openDevice: UsbDevice? get() = device

    /** Устройства, у которых есть интерфейс fastboot. */
    fun candidates(): List<UsbDevice> {
        val map = usb.deviceList ?: return emptyList()
        return map.values.filter { hasFastbootInterface(it) }
    }

    fun hasFastbootInterface(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass != CLASS || intf.interfaceSubclass != SUBCLASS) continue
            // Часть прошивок отдаёт fastboot с протоколом 0x00/0x01 — узнаём по PID.
            if (com.miunlock.app.adb.UsbDeviceKind.canBeFastboot(intf.interfaceProtocol)) {
                return true
            }
        }
        return false
    }

    /** Похоже ли на fastboot по VID, если интерфейс ещё не разобран системой. */
    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    /**
     * Запрашивает доступ к устройству. Результат приходит в [onResult] на главном потоке.
     * На Android 12+ PendingIntent обязан быть mutable, иначе система молча отказывает.
     */
    fun requestPermission(dev: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usb.hasPermission(dev)) {
            onResult(true)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.action != ACTION_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            },
        )
        usb.requestPermission(dev, pi)
    }

    /** Открывает интерфейс и находит bulk-эндпоинты. */
    fun open(dev: UsbDevice): Result<Unit> {
        close()
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null

        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var a: UsbEndpoint? = null
            var b: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) a = ep else b = ep
            }
            if (a == null || b == null) continue
            val exact = intf.interfaceClass == CLASS && intf.interfaceSubclass == SUBCLASS
            if (exact) {
                chosen = intf; inEp = a; outEp = b
                break
            }
            if (chosen == null) {
                chosen = intf; inEp = a; outEp = b
            }
        }

        val intf = chosen ?: return Result.failure(IllegalStateException("не найдены bulk-эндпоинты fastboot"))
        val conn = usb.openDevice(dev) ?: return Result.failure(IllegalStateException("не удалось открыть устройство"))
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return Result.failure(IllegalStateException("интерфейс занят другим приложением"))
        }
        connection = conn
        iface = intf
        epIn = inEp
        epOut = outEp
        device = dev
        return Result.success(Unit)
    }

    fun close() {
        runCatching { iface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null
        iface = null
        epIn = null
        epOut = null
        device = null
    }

    /** Отправляет команду и читает ответ до финального OKAY/FAIL. */
    fun execute(command: String, timeoutMs: Int = TIMEOUT_CMD): Result<FastbootResult> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val out = epOut ?: return Result.failure(IllegalStateException("нет OUT-эндпоинта"))
        val cmd = runCatching { FastbootCodec.buildCommand(command) }.getOrElse { return Result.failure(it) }
        val sent = conn.bulkTransfer(out, cmd, cmd.size, timeoutMs)
        if (sent != cmd.size) {
            return Result.failure(IllegalStateException("команда не отправлена: $sent из ${cmd.size}"))
        }
        return readUntilFinal(timeoutMs)
    }

    /** Читает кадры, копит INFO, возвращает финальный. */
    fun readUntilFinal(timeoutMs: Int = TIMEOUT_CMD): Result<FastbootResult> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val input = epIn ?: return Result.failure(IllegalStateException("нет IN-эндпоинта"))
        val buffer = ByteArray(BUFFER)
        val info = mutableListOf<String>()

        while (true) {
            val len = conn.bulkTransfer(input, buffer, buffer.size, timeoutMs)
            if (len < 0) return Result.failure(IllegalStateException("нет ответа от загрузчика"))
            if (len < 4) return Result.failure(IllegalStateException("битый кадр: $len байт"))
            when (val frame = FastbootCodec.parseFrame(buffer, len)) {
                is FastbootFrame.Info -> info.add(frame.payload)
                is FastbootFrame.Okay -> return Result.success(
                    FastbootResult(FastbootCodec.STATUS_OKAY, frame.payload, info.toList()),
                )
                is FastbootFrame.Fail -> return Result.success(
                    FastbootResult(FastbootCodec.STATUS_FAIL, frame.payload, info.toList()),
                )
                is FastbootFrame.Data -> return Result.success(
                    FastbootResult(FastbootCodec.STATUS_DATA, frame.hexLength, info.toList()),
                )
                is FastbootFrame.Text -> info.add(frame.payload)
                is FastbootFrame.Unknown -> return Result.success(
                    FastbootResult("ERROR", "${frame.prefix}${frame.payload}", info.toList()),
                )
            }
        }
    }

    /** Передаёт данные после ответа DATA. */
    fun sendData(data: ByteArray, timeoutMs: Int = TIMEOUT_DATA): Result<Unit> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val out = epOut ?: return Result.failure(IllegalStateException("нет OUT-эндпоинта"))
        var offset = 0
        while (offset < data.size) {
            val count = minOf(CHUNK, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + count)
            val sent = conn.bulkTransfer(out, chunk, count, timeoutMs)
            if (sent != count) {
                return Result.failure(IllegalStateException("данные оборваны на $offset: $sent из $count"))
            }
            offset += count
        }
        return Result.success(Unit)
    }

    fun deviceLabel(dev: UsbDevice): String {
        val name = listOfNotNull(dev.manufacturerName, dev.productName)
            .joinToString(" ").trim()
        return if (name.isEmpty()) {
            "USB %04x:%04x".format(dev.vendorId, dev.productId)
        } else {
            name
        }
    }

    fun asciiPreview(bytes: ByteArray): String = String(bytes, StandardCharsets.US_ASCII)
}
