package com.linux_core.ui.cpu

import com.linux_core.core.cpu.Sample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM testy pro čistou logiku škálování grafů (bez Compose runtime —
 * `@Composable` funkce v `CpuCharts.kt` tady netestujeme, jen `yScale`).
 * Cílem je ověřit, že prázdné a degenerované vstupy nikdy nedělí nulou
 * ani nevyhodí výjimku.
 */
class CpuChartsTest {

    @Test
    fun `scale prazdneho seznamu nedeli nulou`() {
        val range = yScale(emptyList())
        assertFalse(range.start.isNaN())
        assertFalse(range.endInclusive.isNaN())
        assertTrue(range.endInclusive >= range.start)
    }

    @Test
    fun `scale jednoho vzorku da platny rozsah`() {
        val samples = listOf(Sample(ts = 1L, promile = 500, core = 0, mask = "1"))
        val range = yScale(samples)
        assertFalse(range.start.isNaN())
        assertFalse(range.endInclusive.isNaN())
        assertTrue(range.endInclusive > range.start)
    }

    @Test
    fun `scale stejnych hodnot nedeli nulou`() {
        val samples = listOf(
            Sample(ts = 1L, promile = 500, core = 0, mask = "1"),
            Sample(ts = 2L, promile = 500, core = 0, mask = "1"),
            Sample(ts = 3L, promile = 500, core = 0, mask = "1"),
        )
        val range = yScale(samples)
        assertFalse(range.start.isNaN())
        assertFalse(range.endInclusive.isNaN())
        assertTrue(range.endInclusive > range.start)
    }
}
