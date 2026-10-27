package com.linux_core.ui.cpu

import com.linux_core.core.cpu.Cluster
import com.linux_core.core.cpu.CpuctlState
import com.linux_core.core.cpu.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM testy pro stavovou logiku tabu Monitor ([MonitorLogic]) — žádný
 * Compose runtime, jen čisté funkce, které `MonitorTab.kt` používá pro
 * varování nad prahem, mapování [CpuctlState] a pinovací chipy z clusterů.
 */
class MonitorStateTest {

    private fun sample(ts: Long, promile: Int) = Sample(ts = ts, promile = promile, core = 0, mask = "1")

    @Test
    fun `appka nad prahem po tri vzorky da varovani`() {
        // Tři vzorky po sobě nad prahem (500 promile = 50 %), v rozestupu
        // typickém pro 5s HB smyčku cpuctld.
        val samples = listOf(
            sample(0L, 600),
            sample(5L, 650),
            sample(10L, 700),
        )
        assertTrue(MonitorLogic.isOverThreshold(samples, thresholdPromile = 500))
    }

    @Test
    fun `appka pod prahem varovani nema`() {
        val samples = listOf(
            sample(0L, 100),
            sample(5L, 200),
            sample(10L, 150),
        )
        assertFalse(MonitorLogic.isOverThreshold(samples, thresholdPromile = 500))
    }

    @Test
    fun `appka nad prahem jen jednou varovani nema`() {
        // Jeden krátký špičkový vzorek nesmí spustit trvalé varování —
        // brief žádá "déle než pár minut", ne okamžitý výkyv.
        val samples = listOf(
            sample(0L, 100),
            sample(5L, 900),
            sample(10L, 100),
        )
        assertFalse(MonitorLogic.isOverThreshold(samples, thresholdPromile = 500))
    }

    @Test
    fun `stara binarka da TooOld stav a zadne grafy`() {
        val state = CpuctlState.TooOld("1.3")
        val screen = MonitorLogic.screenStateFor(state)
        assertTrue(screen is MonitorScreenState.Blocked)
        val blocked = screen as MonitorScreenState.Blocked
        assertTrue(blocked.message.contains("1.3"))
        assertTrue(blocked.message.contains("1.4"))
        assertFalse(blocked.canRetry)
    }

    @Test
    fun `chybejici root da NoRoot stav bez moznosti opakovat`() {
        val screen = MonitorLogic.screenStateFor(CpuctlState.NoRoot)
        assertTrue(screen is MonitorScreenState.Blocked)
        val blocked = screen as MonitorScreenState.Blocked
        assertTrue(blocked.message.contains("nh_cpuctl"))
        assertFalse(blocked.canRetry)
    }

    @Test
    fun `nectelny vystup nabizi opakovani a nerika neni root`() {
        val screen = MonitorLogic.screenStateFor(CpuctlState.Unreadable("neco na stderr"))
        assertTrue(screen is MonitorScreenState.Blocked)
        val blocked = screen as MonitorScreenState.Blocked
        assertTrue(blocked.canRetry)
        assertFalse(blocked.message.contains("není root"))
    }

    @Test
    fun `ok stav je provozni`() {
        assertEquals(MonitorScreenState.Ready, MonitorLogic.screenStateFor(CpuctlState.Ok))
    }

    @Test
    fun `chipy jader vznikaji z clusteru, ne z konstanty`() {
        // Reálné zařízení z AGENTS.md: 0-5 @1804 MHz (little), 6-7 @2304 MHz
        // (big) — ne dřívější špatné 0-3/4-7.
        val little = Cluster(id = 0, cores = listOf(0, 1, 2, 3, 4, 5), maxKhz = 1_804_000L, label = "little")
        val big = Cluster(id = 1, cores = listOf(6, 7), maxKhz = 2_304_000L, label = "big")
        val clusters = listOf(little, big)

        val chips = MonitorLogic.pinChips(clusters)

        assertEquals(3, chips.size) // little + big + "vše"
        val littleChip = chips.first { it.label.contains("little") }
        assertEquals("1f", littleChip.maskHex)
        val bigChip = chips.first { it.label.contains("big") }
        assertEquals("c0", bigChip.maskHex)
        val allChip = chips.first { it.maskHex == "ff" }
        assertEquals("ff", allChip.maskHex)
    }

    @Test
    fun `chipy jednoho clusteru davaji jen vse`() {
        val only = Cluster(id = 0, cores = listOf(0, 1, 2, 3), maxKhz = 1_800_000L, label = "všechna")
        val chips = MonitorLogic.pinChips(listOf(only))
        assertEquals(1, chips.size)
        assertEquals("f", chips.first().maskHex)
    }
}
