package com.miunlock.app.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * Минимальный ADB-хост по USB.
 *
 * Нужен ровно для одного: перевести подключённый телефон в fastboot командой
 * `reboot:bootloader`, чтобы не заставлять человека зажимать кнопки.
 *
 * Требования на целевой стороне: включённая «Отладка по USB» и подтверждение
 * диалога авторизации на его экране при первом подключении. Без отладки
 * команда невозможна — это ограничение самого Android, а не приложения.
 */
class AdbClient(
    private val context: Context,
    private val keyDir: java.io.File,
) {

    companion object {
        const val CLASS = 0xFF
        const val SUBCLASS = 0x42
        const val PROTOCOL_ADB = 0x01

        private const val TIMEOUT_MS = 8_000
        private const val BUFFER = 4096
        private const val MAX_PACKET = 1024 * 1024
        const val ACTION_PERMISSION = "com.miunlock.app.ADB_USB_PERMISSION"
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null

    val isOpen: Boolean get() = connection != null

    /** Телефоны, у которых поднят интерфейс отладки. */
    fun candidates(): List<UsbDevice> {
        val map = usb.deviceList ?: return emptyList()
        return map.values.filter { hasAdbInterface(it) }
    }

    fun hasAdbInterface(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass == CLASS &&
                intf.interfaceSubclass == SUBCLASS &&
                intf.interfaceProtocol == PROTOCOL_ADB
            ) {
                return true
            }
        }
        return false
    }

    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    fun requestPermission(dev: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usb.hasPermission(dev)) {
            onResult(true)
            return
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.app.PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = android.content.Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = android.app.PendingIntent.getBroadcast(context, 1, intent, flags)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: android.content.Intent) {
                if (i.action != ACTION_PERMISSION) return
                runCatching { context.unregisterReceiver(this) }
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(ACTION_PERMISSION),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                0
            },
        )
        usb.requestPermission(dev, pending)
    }

    fun open(dev: UsbDevice): Result<Unit> {
        close()
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass != CLASS || intf.interfaceSubclass != SUBCLASS) continue
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
            }
            if (input == null || output == null) continue
            val conn = usb.openDevice(dev) ?: return Result.failure(IllegalStateException("не удалось открыть устройство"))
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                return Result.failure(IllegalStateException("интерфейс занят другим приложением"))
            }
            connection = conn
            iface = intf
            epIn = input
            epOut = output
            device = dev
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("не найден интерфейс отладки"))
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

    /**
     * Рукопожатие: CNXN → AUTH(подпись) → при отказе AUTH(публичный ключ).
     * Возвращает true, если телефон авторизовал нас.
     */
    fun connect(): Result<Unit> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val keys = AdbKey(keyDir)
        val pair = keys.loadOrCreate()
        val privateKey = pair.private as RSAPrivateKey
        val publicKey = pair.public as RSAPublicKey

        send(AdbPacket.encode(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, AdbPacket.payload("host::")))
            .getOrElse { return Result.failure(it) }

        var keySent = false
        repeat(6) {
            val packet = read().getOrElse { return Result.failure(it) }
            when (packet.command) {
                AdbPacket.CNXN -> return Result.success(Unit)
                AdbPacket.AUTH -> when (packet.arg0) {
                    AdbPacket.AUTH_TOKEN -> {
                        if (!keySent) {
                            val signature = keys.signToken(packet.data, privateKey)
                            send(AdbPacket.encode(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, signature))
                                .getOrElse { return Result.failure(it) }
                        } else {
                            return Result.failure(
                                IllegalStateException("телефон не принял ключ: подтвердите отладку на его экране"),
                            )
                        }
                    }
                    AdbPacket.AUTH_SIGNATURE -> {
                        val public = keys.encodePublic(publicKey, "miunlockapp@android")
                        send(AdbPacket.encode(AdbPacket.AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, public))
                            .getOrElse { return Result.failure(it) }
                        keySent = true
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        return Result.failure(IllegalStateException("телефон не завершил авторизацию"))
    }

    /** Открывает shell-стрим с командой `reboot:bootloader`. */
    fun rebootToBootloader(): Result<Unit> {
        send(AdbPacket.encode(AdbPacket.OPEN, 1, 0, AdbPacket.payload("reboot:bootloader")))
            .getOrElse { return Result.failure(it) }
        repeat(4) {
            val packet = read().getOrElse { return Result.success(Unit) }
            when (packet.command) {
                AdbPacket.OKAY -> return Result.success(Unit)
                AdbPacket.CLSE -> return Result.failure(IllegalStateException("телефон закрыл команду"))
            }
        }
        // Ответа OKAY может не быть: телефон уже перезагружается. Это не ошибка.
        return Result.success(Unit)
    }

    private fun send(bytes: ByteArray): Result<Unit> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val out = epOut ?: return Result.failure(IllegalStateException("нет OUT-эндпоинта"))
        var offset = 0
        while (offset < bytes.size) {
            val chunk = minOf(AdbPacket.MAX_DATA, bytes.size - offset)
            val sent = conn.bulkTransfer(out, bytes.copyOfRange(offset, offset + chunk), chunk, TIMEOUT_MS)
            if (sent <= 0) return Result.failure(IllegalStateException("пакет не отправлен"))
            offset += sent
        }
        return Result.success(Unit)
    }

    private fun read(): Result<AdbPacket> {
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val input = epIn ?: return Result.failure(IllegalStateException("нет IN-эндпоинта"))
        // Один bulkTransfer может вернуть меньше, чем просили, поэтому заголовок
        // и тело дочитываются циклами: иначе поток уезжает на середину пакета.
        val header = ByteArray(AdbPacket.HEADER)
        if (!readInto(conn, input, header, 0, AdbPacket.HEADER)) {
            return Result.failure(IllegalStateException("нет ответа от телефона"))
        }
        val size = java.nio.ByteBuffer.wrap(header, 16, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        if (size < 0 || size > MAX_PACKET) {
            return Result.failure(IllegalStateException("битый пакет ADB: длина $size"))
        }
        val full = ByteArray(AdbPacket.HEADER + size)
        header.copyInto(full)
        if (size > 0 && !readInto(conn, input, full, AdbPacket.HEADER, size)) {
            return Result.failure(IllegalStateException("пакет ADB не дочитан"))
        }
        val packet = AdbPacket.decode(full, full.size)
            ?: return Result.failure(IllegalStateException("битый пакет ADB"))
        return Result.success(packet)
    }

    /** Читает ровно [count] байт в [dest] начиная с [offset]; false — таймаут. */
    private fun readInto(
        conn: UsbDeviceConnection,
        input: UsbEndpoint,
        dest: ByteArray,
        offset: Int,
        count: Int,
    ): Boolean {
        var done = 0
        while (done < count) {
            val chunk = minOf(BUFFER, count - done)
            val read = conn.bulkTransfer(input, dest, offset + done, chunk, TIMEOUT_MS)
            if (read <= 0) return false
            done += read
        }
        return true
    }
}
