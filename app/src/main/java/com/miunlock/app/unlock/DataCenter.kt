package com.miunlock.app.unlock

import com.miunlock.app.data.DebugLog
import com.miunlock.app.data.LogLevel
import com.miunlock.app.data.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Дата-центр аккаунта по его uid.
 *
 * Регион аккаунта и дата-центр — **разные вещи**, и это главная ловушка цепочки.
 * Аккаунт, зарегистрированный в Латвии, имеет регион `LV`, то есть зону Europe,
 * но обслуживаться может дата-центром Singapore: Xiaomi распределяет uid по
 * диапазонам, и `ahaUnlock` привязан именно к дата-центру. При несовпадении
 * сервер отвечает `20031` — «привяжите аккаунт», хотя привязка есть.
 *
 * Диапазоны не зашиты в код: официальный эндпоинт Xiaomi отдаёт их списком,
 * поэтому при их изменении ничего править не нужно.
 */
object DataCenter {

    private const val CONFIGURATION_URL = "https://api.account.xiaomi.com/pass/configuration"
    private const val USER_AGENT = "XiaomiPCSuite"

    /**
     * Зона дата-центра для uid или `null`, если определить не удалось
     * (нет сети, uid нечисловой, uid вне всех диапазонов).
     */
    suspend fun zoneForUid(uid: String): String? = withContext(Dispatchers.IO) {
        val numeric = uid.trim().toLongOrNull()
        if (numeric == null) {
            DebugLog.trace(LogTag.API, "дата-центр: uid «$uid» не число, пропускаю")
            return@withContext null
        }
        val ranges = runCatching { fetchRanges() }.getOrElse { error ->
            DebugLog.warn(
                LogTag.API,
                "дата-центр: список диапазонов не получен (${error.message}), беру зону по региону",
            )
            return@withContext null
        }
        val hit = ranges.firstOrNull { (_, bounds) ->
            bounds.any { numeric >= it.first && numeric <= it.last }
        }?.first
        DebugLog.add(
            LogLevel.INFO,
            LogTag.API,
            if (hit != null) {
                "дата-центр аккаунта: $hit (uid $numeric)"
            } else {
                "дата-центр: uid $numeric не попал ни в один диапазон, беру зону по региону"
            },
        )
        hit
    }

    /** Пары «зона → список диапазонов uid» из официального конфига. */
    private fun fetchRanges(): List<Pair<String, List<LongRange>>> {
        val url = URL("$CONFIGURATION_URL?keys=idc")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("конфиг вернул HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val idc = JSONObject(body).optJSONObject("data")?.optJSONObject("idc")
                ?: throw IllegalStateException("в конфиге нет блока idc")
            val out = mutableListOf<Pair<String, List<LongRange>>>()
            for (zone in idc.keys()) {
                val info = idc.optJSONObject(zone) ?: continue
                val ranges = mutableListOf<LongRange>()
                info.optLong("userId.min").takeIf { info.has("userId.min") }?.let { lo ->
                    ranges += lo..info.optLong("userId.max")
                }
                // Дополнительные диапазоны: у одной зоны их может быть несколько.
                val ext = info.optJSONArray("extend.idRange")
                if (ext != null) {
                    for (i in 0 until ext.length()) {
                        val item = ext.optJSONObject(i) ?: continue
                        if (item.has("userId.min")) {
                            ranges += item.optLong("userId.min")..item.optLong("userId.max")
                        }
                    }
                }
                if (ranges.isNotEmpty()) out += zone to ranges
            }
            return out
        } finally {
            connection.disconnect()
        }
    }
}
