package com.miunlock.app.adb

/**
 * Разбор потока ADB на пакеты.
 *
 * USB отдаёт байты как есть: один bulk-трансфер может принести половину
 * заголовка, весь пакет или сразу два пакета. Поэтому данные сначала копятся
 * в буфере, и только потом из него нарезаются целые пакеты.
 */
class AdbFramer {

    private var buffer = ByteArray(0)
    private var length = 0

    /** Сколько байт лежит в буфере и ещё не разобрано. */
    val buffered: Int get() = length

    fun feed(chunk: ByteArray, count: Int = chunk.size) {
        if (count <= 0) return
        ensure(length + count)
        System.arraycopy(chunk, 0, buffer, length, count)
        length += count
    }

    /**
     * Достаёт один целый пакет, если он уже накопился. Иначе null —
     * значит нужно прочитать ещё данных.
     */
    fun next(): AdbPacket? {
        if (length < AdbPacket.HEADER) return null
        val size = le32(buffer, 12)
        if (size < 0 || size > MAX_PACKET) {
            // Заголовок битый: продолжать разбор этого потока бессмысленно.
            length = 0
            return null
        }
        val total = AdbPacket.HEADER + size
        if (length < total) return null
        val packet = AdbPacket.decode(buffer, total)
        consume(total)
        return packet
    }

    fun reset() {
        length = 0
    }

    private fun consume(count: Int) {
        val rest = length - count
        if (rest > 0) System.arraycopy(buffer, count, buffer, 0, rest)
        length = rest
    }

    private fun ensure(capacity: Int) {
        if (buffer.size >= capacity) return
        var next = if (buffer.isEmpty()) 8192 else buffer.size
        while (next < capacity) next *= 2
        buffer = buffer.copyOf(next)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        /** Верхний предел тела пакета: защита от мусора в заголовке. */
        const val MAX_PACKET = 1024 * 1024
    }
}
