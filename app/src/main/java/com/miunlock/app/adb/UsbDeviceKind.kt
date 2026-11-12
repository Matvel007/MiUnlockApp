package com.miunlock.app.adb

/**
 * Какой режим у подключённого телефона.
 *
 * У ADB и fastboot одинаковый класс интерфейса (`0xFF/0x42`), поэтому по
 * классу их не различить: признак — протокол интерфейса и известные PID.
 * Путаница здесь дорого стоит: ADB-пакет, отправленный в fastboot, просто
 * не уходит, и это выглядит как «телефон не отвечает».
 */
object UsbDeviceKind {

    const val PROTOCOL_ADB = 0x01
    const val PROTOCOL_FASTBOOT = 0x03

    const val VID_GOOGLE = 0x18D1
    const val VID_XIAOMI = 0x2717
    const val VID_QUALCOMM = 0x05C6

    /**
     * PID, которые телефон показывает именно в загрузчике.
     * `0x4EE7` — стандартный fastboot Google (его же отдают многие Xiaomi),
     * `0x0FFF` — fastboot Xiaomi, `0xD00D` — режим EDL Qualcomm.
     */
    private val FASTBOOT_PIDS = setOf(0x4EE7, 0x0FFF, 0xD00D, 0x4EE2)

    fun isFastboot(protocol: Int, vendorId: Int, productId: Int): Boolean {
        if (protocol == PROTOCOL_FASTBOOT) return true
        // Некоторые прошивки отдают fastboot с протоколом 0x00 или 0x01:
        // тогда единственный признак — известный PID загрузчика.
        return productId in FASTBOOT_PIDS && vendorId in setOf(VID_GOOGLE, VID_XIAOMI, VID_QUALCOMM)
    }

    fun isAdb(protocol: Int, vendorId: Int, productId: Int): Boolean {
        if (isFastboot(protocol, vendorId, productId)) return false
        return protocol == PROTOCOL_ADB
    }
}
