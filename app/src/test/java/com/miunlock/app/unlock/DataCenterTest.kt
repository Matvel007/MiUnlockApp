package com.miunlock.app.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор диапазонов uid. Сеть здесь не нужна: проверяется только логика
 * выбора зоны по числу, чтобы ошибка в ней не всплывала на живом устройстве.
 *
 * Границы взяты из ответа api.account.xiaomi.com/pass/configuration?keys=idc
 * на 12.11.2026. Если Xiaomi их сдвинет, тест укажет на это первым.
 */
class DataCenterTest {

    /** Копия разбора из [DataCenter], но без сети: диапазоны передаются сюда. */
    private fun zoneFor(
        uid: Long,
        ranges: List<Pair<String, List<LongRange>>>,
    ): String? = ranges.firstOrNull { (_, bounds) ->
        bounds.any { uid >= it.first && uid <= it.last }
    }?.first

    private val ranges = listOf(
        "Singapore" to listOf(1_540_000_000L..1_949_999_999L, 6_150_000_001L..8_150_000_000L),
        "China" to listOf(0L..1_540_000_000L, 2_150_000_000L..4_150_000_000L),
        "Europe" to listOf(8_150_000_001L..8_650_000_000L),
        "Russia" to listOf(4_150_000_001L..5_150_000_000L),
        "India" to listOf(5_150_000_001L..6_150_000_000L),
    )

    @Test
    fun `uid тестера попадает в Сингапур`() {
        // Реальный uid из лога приложения: аккаунт с регионом LV.
        assertEquals("Singapore", zoneFor(1_732_954_770L, ranges))
    }

    @Test
    fun `регион аккаунта не совпадает с дата-центром`() {
        // Та же ловушка, из-за которой сервер отвечал 20031: регион LV даёт
        // зону Europe, а uid обслуживается Сингапуром.
        assertEquals("Europe", UnlockApi.zoneForRegion("LV"))
        assertEquals("Singapore", zoneFor(1_732_954_770L, ranges))
    }

    @Test
    fun `границы диапазонов включительные`() {
        assertEquals("Singapore", zoneFor(1_540_000_000L, ranges))
        assertEquals("Singapore", zoneFor(1_949_999_999L, ranges))
        assertEquals("Europe", zoneFor(8_150_000_001L, ranges))
        assertEquals("Europe", zoneFor(8_650_000_000L, ranges))
    }

    @Test
    fun `uid вне всех диапазонов не даёт зоны`() {
        assertNull(zoneFor(99_000_000_000L, ranges))
    }
}
