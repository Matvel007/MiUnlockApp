package com.miunlock.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant

class TimeSync {
    suspend fun synchronizedNow(): Instant = withContext(Dispatchers.IO) {
        runCatching { queryNtp("ntp1.aliyun.com") }
            .recoverCatching { queryNtp("time.google.com") }
            .getOrElse { Instant.now() }
    }

    private fun queryNtp(host: String): Instant {
        val buffer = ByteArray(48)
        buffer[0] = 0x1B
        DatagramSocket().use { socket ->
            socket.soTimeout = 5_000
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(buffer, buffer.size, address, 123))
            socket.receive(DatagramPacket(buffer, buffer.size))
        }
        val seconds = unsignedInt(buffer, 40)
        val fraction = unsignedInt(buffer, 44)
        val unixSeconds = seconds - 2_208_988_800L
        val nanos = ((fraction.toDouble() / 0x1_0000_0000L.toDouble()) * 1_000_000_000L).toLong()
        return Instant.ofEpochSecond(unixSeconds, nanos)
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
}
