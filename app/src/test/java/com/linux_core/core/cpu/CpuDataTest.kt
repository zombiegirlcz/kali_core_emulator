package com.linux_core.core.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM testy pro čistou logiku parserů TSV výstupů `cpuctl` (bez Android
 * kontextu — `su`/sysfs čtení dělá build caller, sem chodí jen String).
 * Parsery musí být tolerantní: zkrácený/nečitelný řádek zahodit, nikdy
 * nevyhodit výjimku (TSV přichází z `su`, může být useknuté timeoutem).
 */
class CpuDataTest {

    @Test
    fun `cores parsuje TSV a ignoruje hlavicku`() {
        val tsv = """
            core	max_khz	cur_khz	busy_promile	cluster
            0	1804000	1200000	120	0
            1	1804000	1200000	130	0
            2	1804000	1200000	110	0
            3	1804000	1200000	140	0
            4	1804000	1200000	150	0
            5	1804000	1200000	160	0
            6	2304000	2000000	800	1
            7	2304000	2100000	900	1
        """.trimIndent()

        val rows = CpuTsv.cores(tsv)

        assertEquals(8, rows.size)
        assertEquals(CoreRow(0, 1804000, 1200000, 120, 0), rows[0])
        assertEquals(CoreRow(7, 2304000, 2100000, 900, 1), rows[7])
    }

    @Test
    fun `clusters radi vzestupne a pojmenuje little a big`() {
        val rows = (0..5).map { CoreRow(it, 1804000, 1200000, 100, 0) } +
            (6..7).map { CoreRow(it, 2304000, 2000000, 800, 1) }

        val clusters = CpuTsv.clusters(rows)

        assertEquals(2, clusters.size)
        val little = clusters.first { it.maxKhz == 1804000L }
        val big = clusters.first { it.maxKhz == 2304000L }
        assertEquals("little", little.label)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), little.cores)
        assertEquals("big", big.label)
        assertEquals(listOf(6, 7), big.cores)
    }

    @Test
    fun `jediny cluster se jmenuje vsechna`() {
        val rows = (0..7).map { CoreRow(it, 2000000, 1800000, 100, 0) }

        val clusters = CpuTsv.clusters(rows)

        assertEquals(1, clusters.size)
        assertEquals("všechna", clusters[0].label)
        assertEquals((0..7).toList(), clusters[0].cores)
    }

    @Test
    fun `maskOf prevadi jadra na hex`() {
        assertEquals("c0", CpuTsv.maskOf(listOf(6, 7)))
        assertEquals("3f", CpuTsv.maskOf((0..5).toList()))
        assertEquals("0", CpuTsv.maskOf(emptyList()))
    }

    @Test
    fun `history preskoci poskozene radky a zachova poradi`() {
        val tsv = """
            ts	promile	core	mask
            1000	100	0	3f
            porouchany-radek
            1005	200	1	3f
            1010	150	0	c0
            1015	0	7	c0
        """.trimIndent()

        val samples = CpuTsv.history(tsv)

        assertEquals(4, samples.size)
        assertEquals(listOf(1000L, 1005L, 1010L, 1015L), samples.map { it.ts })
        assertTrue(samples.zip(samples.drop(1)).all { (a, b) -> a.ts <= b.ts })
    }

    @Test
    fun `bench cte prazdne mA jako null a dirty jako Boolean`() {
        val tsv = """
            phase	mask	jiffies_per_s	samples	mA_delta	dirty
            A	3f	42.5	12		false
            B	c0	55.0	12	18	true
        """.trimIndent()

        val phases = CpuTsv.bench(tsv)

        assertEquals(2, phases.size)
        assertNull(phases[0].mA)
        assertEquals(false, phases[0].dirty)
        assertEquals(18, phases[1].mA)
        assertEquals(true, phases[1].dirty)
    }

    @Test
    fun `parseVersion pozna starou binarku`() {
        assertEquals(CpuctlState.TooOld("1.3"), parseVersion("1.3"))
        assertEquals(CpuctlState.Ok, parseVersion("1.4"))
    }

    @Test
    fun `parseVersion odlisi chybejici root od zkracene odpovedi`() {
        // Prázdno = `cpuctl` vůbec neodpověděl (není root ani modul).
        assertEquals(CpuctlState.NoRoot, parseVersion(""))
        assertEquals(CpuctlState.NoRoot, parseVersion("   "))
        // Neprázdná, ale nečitelná odpověď: root může být v pořádku a `su`
        // jen zkrátil výstup, nebo se přimíchala hláška ze stderr.
        assertEquals(CpuctlState.Unreadable("1."), parseVersion("1."))
        assertEquals(
            CpuctlState.Unreadable("cpuctl: permission denied"),
            parseVersion("cpuctl: permission denied"),
        )
        // `raw` se zkracuje, aby šel bezpečně zobrazit v UI.
        val long = "x".repeat(200)
        val state = parseVersion(long) as CpuctlState.Unreadable
        assertEquals(64, state.raw.length)
    }

    @Test
    fun `prazdny vstup da prazdny seznam, ne vyjimku`() {
        assertEquals(emptyList<CoreRow>(), CpuTsv.cores(""))
        assertEquals(emptyList<Sample>(), CpuTsv.history(""))
        assertEquals(emptyList<PinEvent>(), CpuTsv.events(""))
        assertEquals(emptyList<BenchPhase>(), CpuTsv.bench(""))
        assertEquals(emptyList<Cluster>(), CpuTsv.clusters(emptyList()))
    }
}
