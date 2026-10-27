package com.linux_core.core.cpu

/**
 * Datové modely a parsery TSV výstupů `cpuctl` (verze 1.4, viz
 * `app/src/main/cpp/cpuctl.c` a design `docs/superpowers/specs/2026-10-22-cpu-apps-tracing-design.md`).
 *
 * Čistá logika bez Android kontextu (žádný `Context`, `File`, sysfs čtení
 * ani `su` volání) — proto JVM-testovatelná. Vstupní řetězec dodává caller
 * (výstup `su -c cpuctl …`), parsery samy nic nečtou.
 *
 * TSV přichází z `su` a může být useknuté timeoutem nebo smíchané s hláškou
 * na stderr → každý parser je tolerantní: neúplný/nečitelný řádek se zahodí,
 * nikdy se nevyhodí výjimka. Prázdný vstup vrací prázdný seznam.
 */

/** Jeden řádek `cpuctl cores`: core  max_khz  cur_khz  busy_promile  cluster */
data class CoreRow(
    val core: Int,
    val maxKhz: Long,
    val curKhz: Long,
    val busyPromile: Int,
    val cluster: Int,
)

/**
 * Skupina jader se stejnou `cpuinfo_max_freq`. `label` se odvozuje z pořadí
 * clusterů podle frekvence: nejnižší = `little`, nejvyšší = `big`, prostřední
 * (je-li víc než dva clustery) = `mid`. Jediný cluster (homogenní SoC) =
 * `všechna`.
 */
data class Cluster(
    val id: Int,
    val cores: List<Int>,
    val maxKhz: Long,
    val label: String,
)

/** Jeden vzorek `cpuctl history <pkg>`: ts  promile  core  mask_hex */
data class Sample(
    val ts: Long,
    val promile: Int,
    val core: Int,
    val mask: String,
)

/** Jedna událost `cpuctl events`: ts  pkg  old_mask  new_mask  who */
data class PinEvent(
    val ts: Long,
    val pkg: String,
    val oldMask: String,
    val newMask: String,
    val who: String,
)

/** Jedna fáze `cpuctl bench`: phase  mask  jiffies_per_s  samples  mA_delta  dirty */
data class BenchPhase(
    val phase: String,
    val mask: String,
    val jiffiesPerS: Double,
    val samples: Int,
    val mA: Int?,
    val dirty: Boolean,
)

/** Stav `cpuctl version` vůči verzi vyžadované appkou. */
sealed interface CpuctlState {
    /** Binárka je dost nová. */
    object Ok : CpuctlState

    /** Binárka hlásí starší verzi, než appka vyžaduje. */
    data class TooOld(val version: String) : CpuctlState

    /** `cpuctl` vůbec neodpověděl — prázdný výstup (bez rootu/modulu). */
    object NoRoot : CpuctlState

    /**
     * `cpuctl` něco vrátil, ale není to verze — odpověď se zkrátila timeoutem
     * `su`, nebo se do stdout přimíchala hláška ze stderr.
     *
     * Nesmí se slít s [NoRoot]: root i modul mohou být v pořádku a UI má v
     * tomhle případě nabídnout opakování, ne hlásit „není root". [raw] je
     * zkrácený na prvních 64 znaků, aby se dal bezpečně zobrazit.
     */
    data class Unreadable(val raw: String) : CpuctlState
}

/** Nejnižší verze `cpuctl`, kterou appka umí číst (history/cores/events/bench). */
private const val REQUIRED_VERSION = "1.4"

/**
 * Rozpozná výstup `cpuctl version`. Prázdný vstup (žádný root, modul
 * nenainstalovaný) → [CpuctlState.NoRoot]; neprázdný, ale nečitelný
 * (zkrácená odpověď, hláška ze stderr) → [CpuctlState.Unreadable].
 * Jinak porovná číselně s [REQUIRED_VERSION] ("major.minor…", chybějící
 * složky = 0).
 */
fun parseVersion(s: String): CpuctlState {
    val version = s.trim()
    if (version.isEmpty()) return CpuctlState.NoRoot

    val parts = version.split(".").map { it.toIntOrNull() }
    if (parts.isEmpty() || parts.any { it == null }) {
        return CpuctlState.Unreadable(version.take(64))
    }

    val required = REQUIRED_VERSION.split(".").map { it.toInt() }
    val got = parts.map { it!! }
    val len = maxOf(required.size, got.size)
    for (i in 0 until len) {
        val g = got.getOrElse(i) { 0 }
        val r = required.getOrElse(i) { 0 }
        if (g > r) return CpuctlState.Ok
        if (g < r) return CpuctlState.TooOld(version)
    }
    return CpuctlState.Ok
}

/** Parsery TSV výstupů `cpuctl`. */
object CpuTsv {

    /** Rozdělí řádek TSV na pole; `\r` na konci (CRLF) oseká. */
    private fun splitLine(line: String): List<String> =
        line.trimEnd('\r').split('\t')

    /** Vrátí neprázdné datové řádky vstupu (prázdné řádky a hlavičku navíc řeší samotný parser pole). */
    private fun dataLines(s: String): List<String> =
        s.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }

    /** `cpuctl cores` → [CoreRow]. Hlavička (nečíselné `core`) i poškozené řádky se zahodí. */
    fun cores(s: String): List<CoreRow> {
        val result = mutableListOf<CoreRow>()
        for (line in dataLines(s)) {
            val f = splitLine(line)
            if (f.size < 5) continue
            val core = f[0].toIntOrNull() ?: continue
            val maxKhz = f[1].toLongOrNull() ?: continue
            val curKhz = f[2].toLongOrNull() ?: continue
            val busy = f[3].toIntOrNull() ?: continue
            val cluster = f[4].toIntOrNull() ?: continue
            result += CoreRow(core, maxKhz, curKhz, busy, cluster)
        }
        return result
    }

    /**
     * Seskupí [CoreRow] podle `cluster` (ignoruje pořadí ve vstupu),
     * seřadí vzestupně podle `maxKhz` a přidělí id 0..n-1 a label.
     */
    fun clusters(rows: List<CoreRow>): List<Cluster> {
        if (rows.isEmpty()) return emptyList()

        val byCluster = rows.groupBy { it.cluster }
            .map { (_, group) ->
                Triple(
                    group.map { it.core }.sorted(),
                    group.first().maxKhz,
                    Unit,
                )
            }
            .sortedBy { it.second }

        val n = byCluster.size
        return byCluster.mapIndexed { index, (cores, maxKhz, _) ->
            val label = when {
                n == 1 -> "všechna"
                index == 0 -> "little"
                index == n - 1 -> "big"
                else -> "mid"
            }
            Cluster(id = index, cores = cores, maxKhz = maxKhz, label = label)
        }
    }

    /** `cpuctl history <pkg>` → [Sample]. */
    fun history(s: String): List<Sample> {
        val result = mutableListOf<Sample>()
        for (line in dataLines(s)) {
            val f = splitLine(line)
            if (f.size < 4) continue
            val ts = f[0].toLongOrNull() ?: continue
            val promile = f[1].toIntOrNull() ?: continue
            val core = f[2].toIntOrNull() ?: continue
            val mask = f[3]
            if (mask.isBlank()) continue
            result += Sample(ts, promile, core, mask)
        }
        return result
    }

    /** `cpuctl events [N]` → [PinEvent]. */
    fun events(s: String): List<PinEvent> {
        val result = mutableListOf<PinEvent>()
        for (line in dataLines(s)) {
            val f = splitLine(line)
            if (f.size < 5) continue
            val ts = f[0].toLongOrNull() ?: continue
            val pkg = f[1]
            val oldMask = f[2]
            val newMask = f[3]
            val who = f[4]
            if (pkg.isBlank() || oldMask.isBlank() || newMask.isBlank() || who.isBlank()) continue
            result += PinEvent(ts, pkg, oldMask, newMask, who)
        }
        return result
    }

    /** `cpuctl bench <pkg> <maskA> <maskB> [sec]` → [BenchPhase]. Prázdné `mA_delta` = `null`. */
    fun bench(s: String): List<BenchPhase> {
        val result = mutableListOf<BenchPhase>()
        for (line in dataLines(s)) {
            val f = splitLine(line)
            if (f.size < 6) continue
            val phase = f[0]
            val mask = f[1]
            val jiffiesPerS = f[2].toDoubleOrNull() ?: continue
            val samples = f[3].toIntOrNull() ?: continue
            val mA = f[4].trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
            val dirty = parseBool(f[5]) ?: continue
            if (phase.isBlank() || mask.isBlank()) continue
            result += BenchPhase(phase, mask, jiffiesPerS, samples, mA, dirty)
        }
        return result
    }

    /** Tolerantní bool parser pro pole `dirty` ("true"/"false"/"1"/"0"). */
    private fun parseBool(s: String): Boolean? = when (s.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /** Převede seznam jader na hex masku (bez `0x`, bez vedoucích nul; prázdný seznam → `"0"`). */
    fun maskOf(cores: List<Int>): String {
        var mask = 0L
        for (c in cores) if (c in 0..63) mask = mask or (1L shl c)
        return mask.toString(16)
    }
}
