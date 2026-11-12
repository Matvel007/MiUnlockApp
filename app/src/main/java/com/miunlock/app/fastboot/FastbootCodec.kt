package com.miunlock.app.fastboot

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Ответ загрузчика. Формат кадра: 4 байта ASCII-префикса + payload.
 *
 * ```
 * OKAY<payload>    команда выполнена
 * FAIL<payload>    отказ
 * INFO<payload>    информационная строка, может повторяться
 * DATA<8 hex>      устройство ждёт N байт
 * TEXT<payload>    текстовый вывод
 * ```
 */
sealed class FastbootFrame {
    data class Okay(val payload: String) : FastbootFrame()
    data class Fail(val payload: String) : FastbootFrame()
    data class Info(val payload: String) : FastbootFrame()
    data class Data(val length: Int, val hexLength: String) : FastbootFrame()
    data class Text(val payload: String) : FastbootFrame()
    data class Unknown(val prefix: String, val payload: String) : FastbootFrame()
}

/** Полный результат команды: финальный кадр плюс накопленные INFO-строки. */
data class FastbootResult(
    val status: String,
    val payload: String,
    val infoLines: List<String> = emptyList(),
) {
    val isOkay: Boolean get() = status == FastbootCodec.STATUS_OKAY
}

/**
 * Кодирование команд и разбор кадров. Вынесено отдельно от USB, чтобы
 * проверялось юнит-тестами без устройства.
 */
object FastbootCodec {

    const val STATUS_OKAY = "OKAY"
    const val STATUS_FAIL = "FAIL"
    const val STATUS_INFO = "INFO"
    const val STATUS_DATA = "DATA"
    const val STATUS_TEXT = "TEXT"

    /** Команда — ASCII без префикса; загрузчики не принимают длиннее 64 байт. */
    const val MAX_COMMAND = 64

    fun buildCommand(command: String): ByteArray {
        val bytes = command.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size <= MAX_COMMAND) { "команда длиннее $MAX_COMMAND байт: $command" }
        return bytes
    }

    /** Длина для `stage:`/`download:` — ровно 8 hex-цифр в нижнем регистре. */
    fun hexLength(size: Int): String = String.format(Locale.US, "%08x", size)

    fun parseFrame(bytes: ByteArray, length: Int = bytes.size): FastbootFrame {
        if (length < 4) return FastbootFrame.Unknown("", "")
        val prefix = String(bytes, 0, 4, StandardCharsets.US_ASCII)
        val payload = if (length > 4) String(bytes, 4, length - 4, StandardCharsets.UTF_8).trim() else ""
        return when (prefix) {
            STATUS_OKAY -> FastbootFrame.Okay(payload)
            STATUS_FAIL -> FastbootFrame.Fail(payload)
            STATUS_INFO -> FastbootFrame.Info(payload)
            STATUS_DATA -> FastbootFrame.Data(payload.toIntOrNull(16) ?: 0, payload)
            STATUS_TEXT -> FastbootFrame.Text(payload)
            else -> FastbootFrame.Unknown(prefix, payload)
        }
    }

    /**
     * Склеивает токен из ответа. Xiaomi отдаёт его разбитым на несколько строк
     * `INFOtoken: <часть>` — их нужно соединить по порядку, иначе токен битый.
     * Если строк нет, берём payload (`OKAYtoken: ...`).
     */
    fun mergeToken(infoLines: List<String>, payload: String): String {
        // Только строки с явным "token:" — остальные INFO-строки (product, serialno,
        // secure и прочие) к токену отношения не имеют и склеили бы мусор.
        val parts = infoLines.mapNotNull { line ->
            val idx = line.indexOf("token:", ignoreCase = true)
            if (idx >= 0) line.substring(idx + "token:".length).trim().ifEmpty { null } else null
        }
        if (parts.isNotEmpty()) return parts.joinToString("")

        val idx = payload.indexOf("token:", ignoreCase = true)
        return if (idx >= 0) payload.substring(idx + "token:".length).trim() else ""
    }

    /**
     * Codename из ответа `getvar:product`. Приходит либо в payload
     * (`OKAYredwood`), либо INFO-строкой (`INFOproduct: redwood`).
     */
    fun parseProduct(payload: String, infoLines: List<String>): String {
        if (payload.isNotEmpty()) {
            val p = payload.removePrefix("product:").trim()
            if (p.isNotEmpty()) return p
        }
        for (line in infoLines) {
            val clean = line.removePrefix("product:").trim()
            if (clean.isNotEmpty()) return clean
        }
        return ""
    }

    /** Первая пара ключ=значение из ответа getvar, если нужен произвольный ключ. */
    fun parseGetVar(key: String, payload: String, infoLines: List<String>): String {
        val needle = "$key:"
        for (line in infoLines + payload) {
            val idx = line.indexOf(needle, ignoreCase = true)
            if (idx >= 0) return line.substring(idx + needle.length).trim()
        }
        return ""
    }
}
