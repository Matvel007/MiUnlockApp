package com.miunlock.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат экспорта лога: по нему добровольцы присылают отчёт, он должен быть стабильным. */
class DebugLogTextTest {

    private val zone = ZoneOffset.UTC
    private val moment = Instant.parse("2026-01-02T10:11:12.345Z")

    private fun event(level: LogLevel, tag: LogTag, message: String) =
        DebugEvent(moment, level, tag, message)

    @Test
    fun `шапка идёт до событий`() {
        val text = DebugLog.buildLogText(
            header = listOf("mode" to "unlock", "app" to "1.4 (6)"),
            events = listOf(event(LogLevel.INFO, LogTag.APP, "старт")),
            zone = zone,
        )
        val lines = text.trim().lines()
        assertEquals("=== MiUnlockApp log ===", lines[0])
        assertEquals("mode: unlock", lines[1])
        assertEquals("app: 1.4 (6)", lines[2])
        assertEquals("--- events ---", lines[3])
        assertEquals("10:11:12.345 I app  старт", lines[4])
    }

    @Test
    fun `уровень обозначен буквой`() {
        val text = DebugLog.buildLogText(
            header = emptyList(),
            events = listOf(
                event(LogLevel.TRACE, LogTag.FASTBOOT, "a"),
                event(LogLevel.WARN, LogTag.USB, "b"),
                event(LogLevel.ERROR, LogTag.API, "c"),
            ),
            zone = zone,
        )
        assertTrue(text.contains("10:11:12.345 T fb   a"))
        assertTrue(text.contains("10:11:12.345 W usb  b"))
        assertTrue(text.contains("10:11:12.345 E api  c"))
    }

    @Test
    fun `пустой лог содержит только шапку`() {
        val text = DebugLog.buildLogText(listOf("events" to "0"), emptyList(), zone)
        assertEquals("=== MiUnlockApp log ===\nevents: 0\n--- events ---\n", text)
    }

    @Test
    fun `события идут в том же порядке`() {
        val text = DebugLog.buildLogText(
            header = emptyList(),
            events = listOf(
                event(LogLevel.INFO, LogTag.APP, "первое"),
                event(LogLevel.INFO, LogTag.APP, "второе"),
            ),
            zone = zone,
        )
        assertTrue(text.indexOf("первое") < text.indexOf("второе"))
    }
}
