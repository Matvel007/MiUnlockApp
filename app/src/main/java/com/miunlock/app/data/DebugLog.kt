package com.miunlock.app.data

import android.os.Build
import android.util.Log
import com.miunlock.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Важность события. В экспорте обозначается одной буквой: T, I, W, E. */
enum class LogLevel(val letter: Char) {
    TRACE('T'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/** Раздел, к которому относится событие: по нему удобно искать в длинном логе. */
enum class LogTag(val label: String) {
    APP("app"),
    UI("ui"),
    USB("usb"),
    FASTBOOT("fb"),
    API("api"),
    AUTH("auth"),
}

data class DebugEvent(
    val time: Instant,
    val level: LogLevel,
    val tag: LogTag,
    val message: String,
)

/**
 * Журнал для диагностики. Держит подробные события и умеет отдать их одним
 * текстом вместе с шапкой устройства — чтобы человек мог прислать лог целиком,
 * а не пересказывать, что видел на экране.
 */
object DebugLog {

    /** С запасом: подробный лог fastboot быстро растёт. */
    private const val MAX_EVENTS = 3_000

    /** Ограничение по объёму, чтобы копирование не превращалось в мегабайты. */
    private const val MAX_CHARS = 300_000

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    val events = _events.asStateFlow()

    /** Что сейчас открыто: нужно в шапке лога, чтобы понять, о каком режиме речь. */
    @Volatile
    private var mode: String = "app"

    /** Выбранный язык интерфейса: от него зависит, на каком языке тексты в логе. */
    @Volatile
    private var uiLanguage: String = "ru"

    /**
     * Данные сессии для воспроизведения запросов вне телефона.
     * Заполняются перед каждым обращением к серверу и попадают в экспорт:
     * по ним запрос повторяется один в один, без переспрашивания человека.
     */
    @Volatile
    private var authDump: String = ""

    fun setAuthDump(value: String) {
        authDump = value
    }

    fun setMode(value: String) {
        mode = value
    }

    fun setLanguage(value: String) {
        uiLanguage = value
    }

    fun add(message: String) = add(LogLevel.INFO, LogTag.APP, message)

    fun add(level: LogLevel, tag: LogTag, message: String) {
        // update атомарен: пишут сетевые потоки, USB-поток и UI одновременно.
        _events.update { current ->
            val next = current + DebugEvent(Instant.now(), level, tag, message)
            val trimmed = if (next.size > MAX_EVENTS) next.takeLast(MAX_EVENTS) else next
            dropBySize(trimmed)
        }
        val line = "[${tag.label}] $message"
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.TRACE -> Log.v(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
        }
    }

    fun trace(tag: LogTag, message: String) = add(LogLevel.TRACE, tag, message)
    fun warn(tag: LogTag, message: String) = add(LogLevel.WARN, tag, message)
    fun error(tag: LogTag, message: String) = add(LogLevel.ERROR, tag, message)

    fun clear() {
        _events.value = emptyList()
        Log.i(TAG, "Журнал очищен")
    }

    /**
     * Весь лог одним текстом: шапка с устройством и сборкой, затем события
     * в прямом порядке. Именно это уходит в буфер обмена по кнопке.
     */
    fun exportText(): String {
        val header = listOf(
            "mode" to mode,
            "app" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "ui" to uiLanguage,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "device" to "${Build.DEVICE} / ${Build.PRODUCT} / ${Build.BOARD}",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "abi" to Build.SUPPORTED_ABIS.joinToString(", "),
            "locale" to Locale.getDefault().toString(),
            "time" to Instant.now().atZone(ZoneId.systemDefault()).format(stampFormat),
            "events" to _events.value.size.toString(),
        )
        return buildLogText(header, _events.value, authDump)
    }

    /**
     * Сборка текста без обращения к системе: шапка парами «ключ — значение»,
     * затем события. Вынесено отдельно, чтобы формат можно было проверить тестом.
     */
    internal fun buildLogText(
        header: List<Pair<String, String>>,
        events: List<DebugEvent>,
        authDump: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("=== MiUnlockApp log ===")
        header.forEach { (key, value) -> appendLine("$key: $value") }
        if (authDump.isNotBlank()) {
            appendLine("--- auth dump ---")
            appendLine(authDump)
            appendLine("--- end auth dump ---")
        }
        appendLine("--- events ---")
        events.forEach { event ->
            val time = event.time.atZone(zone).format(timeFormat)
            appendLine("$time ${event.level.letter} ${event.tag.label.padEnd(4)} ${event.message}")
        }
    }

    /** Время события для отрисовки в консоли. */
    fun formatTime(event: DebugEvent): String =
        event.time.atZone(ZoneId.systemDefault()).format(timeFormat)

    /** Режет старые события, если лог перевалил за лимит по символам. */
    private fun dropBySize(events: List<DebugEvent>): List<DebugEvent> {
        var total = 0
        var index = events.size
        while (index > 0) {
            total += events[index - 1].message.length + 40
            if (total > MAX_CHARS) break
            index--
        }
        return if (index <= 0) events else events.subList(index, events.size)
    }

    private const val TAG = "MiUnlockDebug"
}
