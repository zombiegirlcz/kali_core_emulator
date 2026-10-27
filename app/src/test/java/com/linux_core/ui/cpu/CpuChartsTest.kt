package com.linux_core.ui.cpu

import com.linux_core.core.cpu.Cluster
import com.linux_core.core.cpu.Sample
import org.junit.Assert.assertEquals
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

    @Test
    fun `scale nejde pod nulu ani pri zaporne hodnote`() {
        // Záporné promile by z `cpuctl` nemělo přijít, ale parser je tolerantní
        // a graf nesmí kreslit pod nulovou osu.
        val samples = listOf(Sample(ts = 1L, promile = -200, core = 0, mask = "1"))
        val range = yScale(samples)
        assertTrue("start = ${range.start}", range.start >= 0f)
        assertTrue(range.endInclusive > range.start)
    }

    // ── zařazení clusteru: barva a značka musí vycházet ze stejného zdroje ──

    private fun cluster(id: Int, maxKhz: Long, cores: List<Int>) =
        Cluster(id = id, cores = cores, maxKhz = maxKhz, label = "c$id")

    @Test
    fun `clusterTier radi podle frekvence, ne podle id`() {
        // Pořadí v seznamu i id jsou schválně naopak než frekvence.
        val big = cluster(0, 2_304_000L, listOf(6, 7))
        val little = cluster(1, 1_804_000L, listOf(0, 1, 2, 3, 4, 5))
        val clusters = listOf(big, little)
        assertEquals(ClusterTier.LITTLE, clusterTier(little, clusters))
        assertEquals(ClusterTier.BIG, clusterTier(big, clusters))
    }

    @Test
    fun `clusterTier da MID prostrednimu ze tri`() {
        val little = cluster(0, 1_800_000L, listOf(0, 1, 2, 3))
        val mid = cluster(1, 2_200_000L, listOf(4, 5, 6))
        val big = cluster(2, 2_900_000L, listOf(7))
        val clusters = listOf(little, mid, big)
        assertEquals(ClusterTier.MID, clusterTier(mid, clusters))
    }

    @Test
    fun `clusterTier u jednoho clusteru nerozlisuje a nema znacku`() {
        val only = cluster(0, 1_800_000L, listOf(0, 1, 2, 3, 4, 5, 6, 7))
        val tier = clusterTier(only, listOf(only))
        assertEquals(ClusterTier.SINGLE, tier)
        // Homogenní SoC: není co rozlišovat, značka by byla jen šum.
        assertEquals("", tier.tag)
    }

    @Test
    fun `clusterTier bez clusteru da UNKNOWN`() {
        assertEquals(ClusterTier.UNKNOWN, clusterTier(null, emptyList()))
        val only = cluster(0, 1_800_000L, listOf(0))
        assertEquals(ClusterTier.UNKNOWN, clusterTier(null, listOf(only)))
        // Cluster, který v seznamu není (nekonzistentní vstup z `cpuctl`).
        assertEquals(ClusterTier.UNKNOWN, clusterTier(cluster(9, 1L, listOf(9)), listOf(only)))
    }

    @Test
    fun `cluster nenese informaci jen barvou`() {
        // Global Constraints: rozlišení nikdy jen odstínem. Každé zařazení,
        // které se vizuálně rozlišuje barvou, musí mít i neprázdnou značku.
        val little = cluster(0, 1_800_000L, listOf(0))
        val mid = cluster(1, 2_200_000L, listOf(1))
        val big = cluster(2, 2_900_000L, listOf(2))
        val clusters = listOf(little, mid, big)
        val tiers = clusters.map { clusterTier(it, clusters) }
        tiers.forEach { assertTrue("$it má prázdnou značku", it.tag.isNotEmpty()) }
        // A značky se nesmí opakovat, jinak nerozliší nic.
        assertEquals(tiers.size, tiers.map { it.tag }.distinct().size)
        // Barvy jdou ruku v ruce se značkami a taky se neopakují.
        assertEquals(tiers.size, tiers.map { colorForTier(it) }.distinct().size)
    }

    @Test
    fun `clusterOf najde jadro a neni zavisle na poradi`() {
        val little = cluster(0, 1_800_000L, listOf(0, 1))
        val big = cluster(1, 2_900_000L, listOf(2, 3))
        val clusters = listOf(big, little)
        assertEquals(little, clusterOf(1, clusters))
        assertEquals(big, clusterOf(3, clusters))
        assertEquals(null, clusterOf(7, clusters))
    }
}
