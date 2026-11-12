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

        /** Обычный ответ телефона приходит быстро. */
        private const val TIMEOUT_MS = 8_000
        /** Первый ответ adbd: телефон может думать дольше. */
        private const val FIRST_TIMEOUT_MS = 15_000
        /** После отправки ключа телефон показывает диалог: человек должен успеть нажать. */
        private const val AUTH_TIMEOUT_MS = 60_000
        private const val BUFFER = 4096
        const val ACTION_PERMISSION = "com.miunlock.app.ADB_USB_PERMISSION"
    }

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private val framer = AdbFramer()

    val isOpen: Boolean get() = connection != null

    /** Телефоны, у которых поднят интерфейс отладки. */
    fun candidates(): List<UsbDevice> {
        val map = usb.deviceList ?: return emptyList()
        return map.values.filter { hasAdbInterface(it) }
    }

    /**
     * Признак интерфейса отладки. Протокол проверяем мягко: у части прошивок
     * он приходит как 0x00, и строгая проверка на 0x01 отбрасывала живой ADB.
     */
    fun hasAdbInterface(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass != CLASS || intf.interfaceSubclass != SUBCLASS) continue
            // Fastboot отличается от ADB только протоколом/PID. Если этого не
            // учитывать, в загрузчик уходит ADB-пакет и «телефон молчит».
            if (UsbDeviceKind.isAdb(intf.interfaceProtocol, dev.vendorId, dev.productId)) return true
        }
        return false
    }

    /** Телефон уже в загрузчике — ADB тут бессилен, его надо просто найти как fastboot. */
    fun isInFastboot(dev: UsbDevice): Boolean {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (UsbDeviceKind.isFastboot(intf.interfaceProtocol, dev.vendorId, dev.productId)) return true
        }
        return false
    }

    /** Все подключённые устройства с их интерфейсами — для журнала, когда ADB не нашёлся. */
    fun allDevicesDiagnostics(): String {
        val list = usb.deviceList?.values.orEmpty()
        if (list.isEmpty()) return "USB: устройств не видно вообще"
        return list.joinToString(" || ") { diagnostics(it) }
    }

    /** Подробности USB для журнала: без них не понять, куда мы вообще пишем. */
    fun diagnostics(dev: UsbDevice): String = buildString {
        append("USB ${dev.deviceName} VID=%04X PID=%04X".format(dev.vendorId, dev.productId))
        append(" интерфейсов=").append(dev.interfaceCount)
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            append(" | #").append(i)
            append(" class=%02X sub=%02X proto=%02X".format(
                intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol,
            ))
            append(" ep=").append(intf.endpointCount)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                append("[").append("%02X".format(ep.address))
                append(if (ep.direction == UsbConstants.USB_DIR_IN) " IN" else " OUT")
                append(" ").append(ep.type).append(" ").append(ep.maxPacketSize).append("b]")
            }
        }
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
        log(diagnostics(dev))
        // Сначала интерфейс с протоколом ADB, иначе любой 0xFF/0x42.
        val order = (0 until dev.interfaceCount).map { dev.getInterface(it) }
            .filter { it.interfaceClass == CLASS && it.interfaceSubclass == SUBCLASS }
            .sortedByDescending { it.interfaceProtocol == PROTOCOL_ADB }
        for (intf in order) {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
            }
            if (input == null || output == null) {
                log("USB интерфейс без пары bulk-эндпоинтов, пропускаю")
                continue
            }
            val conn = usb.openDevice(dev)
                ?: return Result.failure(IllegalStateException("не удалось открыть устройство"))
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                return Result.failure(IllegalStateException("интерфейс занят другим приложением"))
            }
            conn.setInterface(intf)
            // После claim на эндпоинтах может висеть halt от прошлого хозяина:
            // тогда запись уходит в никуда, а чтение сразу отдаёт ошибку.
            clearHalt(conn, input)
            clearHalt(conn, output)
            connection = conn
            iface = intf
            epIn = input
            epOut = output
            device = dev
            log("USB открыт: IN=%02X OUT=%02X".format(input.address, output.address))
            return Result.success(Unit)
        }
        return Result.failure(IllegalStateException("не найден интерфейс отладки"))
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT) — стандартный способ снять залипший эндпоинт. */
    private fun clearHalt(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        runCatching {
            conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1_000)
        }
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
     * Рукопожатие с телефоном.
     *
     * По протоколу: хост шлёт CNXN; телефон, если не знает нас, отвечает
     * `AUTH` с токеном. Хост подписывает токен и отправляет `AUTH` с подписью.
     * Если подпись не подошла, телефон присылает **новый токен** — тогда надо
     * отдать публичный ключ, и только после этого он покажет диалог «Разрешить
     * отладку по USB?». Ответ на всё это — CNXN.
     */
    fun connect(): Result<Unit> = kotlinx.coroutines.runBlocking { connectSuspend() }

    suspend fun connectSuspend(): Result<Unit> {
        val keys = AdbKey(keyDir)
        val pair = keys.loadOrCreate()
        val privateKey = pair.private as RSAPrivateKey
        val publicKey = pair.public as RSAPublicKey

        framer.reset()
        // Первый пакет от adbd может не прийти: он иногда принимает соединение
        // не с первого раза. Повторяем CNXN, прежде чем объявить провал.
        var packet: AdbPacket? = null
        var attempt = 0
        while (packet == null) {
            attempt++
            send(AdbPacket.encode(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, AdbPacket.payload("host::")))
                .getOrElse { return Result.failure(it) }
            log("ADB → CNXN host:: (попытка $attempt)")
            val result = readPacket(FIRST_TIMEOUT_MS)
            if (result.isSuccess) {
                packet = result.getOrNull()
                break
            }
            if (attempt >= 3) {
                val hint = device?.let { diagnostics(it) } ?: ""
                return Result.failure(
                    IllegalStateException(
                        "телефон не отвечает на ADB. ${result.exceptionOrNull()?.message ?: ""}. $hint",
                    ),
                )
            }
            framer.reset()
            kotlinx.coroutines.delay(1_200)
        }

        var signatureSent = false
        var keySent = false
        repeat(8) {
            val current = packet
            if (current == null) {
                val next = readPacket(if (keySent) AUTH_TIMEOUT_MS else TIMEOUT_MS).getOrElse { error ->
                    return Result.failure(
                        IllegalStateException(
                            if (keySent) {
                                "телефон не подтвердил отладку. Разрешите доступ на его экране и повторите"
                            } else {
                                "телефон не отвечает: ${error.message}"
                            },
                        ),
                    )
                }
                packet = next
            }
            val p = packet ?: return Result.failure(IllegalStateException("нет пакета ADB"))
            packet = null
            log("ADB ← ${name(p.command)} arg0=${p.arg0} data=${p.data.size}b")
            when (p.command) {
                AdbPacket.CNXN -> return Result.success(Unit)
                AdbPacket.CLSE -> return Result.failure(
                    IllegalStateException("телефон закрыл соединение — разрешите отладку на его экране"),
                )
                AdbPacket.AUTH -> when (p.arg0) {
                    // Новый токен: сначала пробуем подпись, потом отдаём ключ.
                    AdbPacket.AUTH_TOKEN -> {
                        if (!signatureSent) {
                            val signature = keys.signToken(p.data, privateKey)
                            send(AdbPacket.encode(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, signature))
                                .getOrElse { return Result.failure(it) }
                            signatureSent = true
                            log("ADB → AUTH SIGNATURE")
                        } else if (!keySent) {
                            val public = keys.encodePublic(publicKey, "miunlockapp@android")
                            send(AdbPacket.encode(AdbPacket.AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, public))
                                .getOrElse { return Result.failure(it) }
                            keySent = true
                            log("ADB → AUTH RSAPUBLICKEY (${public.size}b), ждём подтверждения на телефоне")
                        } else {
                            return Result.failure(
                                IllegalStateException("телефон отклонил ключ — удалите старое разрешение отладки и повторите"),
                            )
                        }
                    }
                    // Телефон просит ключ сразу.
                    else -> if (!keySent) {
                        val public = keys.encodePublic(publicKey, "miunlockapp@android")
                        send(AdbPacket.encode(AdbPacket.AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, public))
                            .getOrElse { return Result.failure(it) }
                        keySent = true
                        log("ADB → AUTH RSAPUBLICKEY (${public.size}b)")
                    }
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
            val packet = readPacket().getOrElse { return Result.success(Unit) }
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

    /**
     * Читает следующий пакет. Данные копятся в [framer], потому что USB может
     * отдать половину пакета или сразу два — резать поток «ровно по размеру»
     * нельзя, иначе разбор уезжает.
     */
    private fun readPacket(timeoutMs: Int = TIMEOUT_MS): Result<AdbPacket> {
        framer.next()?.let { return Result.success(it) }
        val conn = connection ?: return Result.failure(IllegalStateException("устройство не открыто"))
        val input = epIn ?: return Result.failure(IllegalStateException("нет IN-эндпоинта"))
        val chunk = ByteArray(BUFFER)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val read = conn.bulkTransfer(input, chunk, chunk.size, TIMEOUT_MS)
            if (read < 0) return Result.failure(IllegalStateException("ошибка чтения USB ($read)"))
            if (read == 0) continue
            framer.feed(chunk, read)
            framer.next()?.let { return Result.success(it) }
        }
        return Result.failure(IllegalStateException("нет ответа от телефона"))
    }

    private fun log(message: String) {
        runCatching { com.miunlock.app.data.DebugLog.add(message) }
    }

    private fun name(command: Int): String = when (command) {
        AdbPacket.CNXN -> "CNXN"
        AdbPacket.AUTH -> "AUTH"
        AdbPacket.OPEN -> "OPEN"
        AdbPacket.OKAY -> "OKAY"
        AdbPacket.CLSE -> "CLSE"
        AdbPacket.WRTE -> "WRTE"
        else -> "0x%08X".format(command)
    }
}
