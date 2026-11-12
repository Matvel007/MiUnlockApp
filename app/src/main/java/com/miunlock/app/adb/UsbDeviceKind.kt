package com.miunlock.app.adb

/**
 * Режим подключённого телефона.
 *
 * ADB и fastboot объявляют одинаковый класс интерфейса (`0xFF/0x42`), поэтому
 * по классу их не различить. Различает их протокол интерфейса, и это штатный
 * признак из USB-дескриптора, а не таблица моделей:
 *
 * - `0x01` — ADB (обычная система с включённой отладкой);
 * - `0x03` — fastboot (загрузчик);
 * - `0x00` — прошивка протокол не назвала, режим неизвестен.
 *
 * Таблицы VID/PID здесь намеренно нет: она не покроет новые устройства,
 * а ошибка в ней дорога — fastboot, принятый за ADB, вообще не отвечает.
 */
object UsbDeviceKind {

    const val PROTOCOL_ADB = 0x01
    const val PROTOCOL_FASTBOOT = 0x03

    enum class Kind {
        ADB,
        FASTBOOT,
        /** Протокол не указан: режим придётся выяснять попыткой. */
        UNKNOWN,
    }

    fun of(protocol: Int): Kind = when (protocol) {
        PROTOCOL_ADB -> Kind.ADB
        PROTOCOL_FASTBOOT -> Kind.FASTBOOT
        else -> Kind.UNKNOWN
    }

    /** Интерфейс может быть ADB (точно ADB или неизвестный). */
    fun canBeAdb(protocol: Int): Boolean = of(protocol) != Kind.FASTBOOT

    /** Интерфейс может быть fastboot (точно fastboot или неизвестный). */
    fun canBeFastboot(protocol: Int): Boolean = of(protocol) != Kind.ADB
}
