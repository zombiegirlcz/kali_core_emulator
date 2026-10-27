package com.linux_core.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.linux_core.core.cpu.Cluster
import com.linux_core.core.cpu.CoreRow
import com.linux_core.core.cpu.CpuTsv
import com.linux_core.core.cpu.CpuctlState
import com.linux_core.core.cpu.Sample
import com.linux_core.core.cpu.parseVersion
import com.linux_core.ui.cpu.AppDetail
import com.linux_core.ui.cpu.CpuColors
import com.linux_core.ui.cpu.MonitorAppRow
import com.linux_core.ui.cpu.MonitorLogic
import com.linux_core.ui.cpu.MonitorTab
import com.linux_core.ui.cpu.MonitorWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Jak často se při otevřeném tabu Monitor dovzorkovává zátěž appek a jader. */
private const val SAMPLE_INTERVAL_MS = 5_000L

/**
 * Přehled CPU zátěže ostatních aplikací + přišpendlení na jádra.
 *
 * Tři taby (Task 9 + 10 z plánu `docs/superpowers/plans/2026-10-22-cpu-apps-tracing.md`):
 * MONITOR je hotový a napojený na [MonitorTab]/[MonitorLogic]; CORES a BENCH
 * jsou vědomě jen prázdný stav "připravuje se" — to je Task 10, nepředbíhat.
 *
 * Řízení cizích procesů vyžaduje root — mluví přímo s /system/bin/cpuctl
 * (Magisk modul nh_cpuctl) přes `su -c`, stejný vzor jako runCpuBoost.
 * Pin i čtení jsou omezené na aplikace (uid >= 10000) přímo v cpuctl.c;
 * systémové procesy se nikdy nepinují.
 *
 * Clustery jader se **detekují z `cpuinfo_max_freq`**, nikdy natvrdo:
 * původní chipy "little (0-3)" / "big (4-7)" byly na reálném zařízení
 * špatně (0–5 little @1804 MHz, 6–7 big @2304 MHz). `cpuinfo_max_freq` je
 * world-readable (-r--r--r--), takže to zvládne i app uid bez roota a bez
 * závislosti na verzi cpuctl.
 *
 * `cpuctl app-pin` bere **hex masku**, ne rozsah: posílat "0-3" znamenalo
 * `strtoul("0-3", 16)` → odmítnuto s "Usage:" a `rc=0`, takže UI hlásilo
 * úspěch a nestalo se nic. Pin proto jde vždy jako `maskHex`.
 *
 * `cpuctl app-pin` **vždy** zapisuje trvalé pravidlo `app.<pkg>`
 * (`cmd_app_pin` v `cpuctl.c` to dělá bez ohledu na argumenty) — žádný
 * přepínač "jednorázově vs. trvale" v UI, protože by nic neměnil a lhal by.
 * Jednorázový pin (`cpuctl pin <mask> <pid>`) existuje, ale `cpuctl apps`
 * nevrací PID, takže z UI nejde použít bez změny C kódu.
 *
 * `cpuctl history`/`events` (Task 2) ještě neexistují → Monitor si vzorky
 * drží sám v paměti po dobu, co je obrazovka otevřená ([AppSampleBuffer]),
 * dovzorkovává periodicky každých [SAMPLE_INTERVAL_MS] ms, dokud je tab
 * MONITOR aktivní. `cpuctl cores` (Task 1, v1.4) už existuje a používá se
 * pro pás jader; frekvenční odhad ze sysfs je jen fallback a UI ho značí
 * jako odhad, nikdy jako měření (viz `MonitorLogic.coreLoad`).
 * Nainstalovaná binárka může být ještě 1.3 → [CpuctlState.TooOld]/
 * [CpuctlState.NoRoot]/[CpuctlState.Unreadable] se musí ukázat uživateli,
 * ne zamlčet jako prázdný graf.
 */
class CpuAppsActivity : ComponentActivity() {

    data class AppRow(val pkg: String, val cpu: Double, val uid: Int, val mask: String)

    enum class CpuTab(val title: String) { MONITOR("Monitor"), CORES("Jádra"), BENCH("Benchmark") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CpuAppsScreen() }
    }

    /** Spustí `cpuctl <args>` jako root. Vrací (exit, výstup). */
    private fun cpuctl(args: String): Pair<Int, String> = try {
        val pb = ProcessBuilder("su", "-c", "/system/bin/cpuctl $args")
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(12, TimeUnit.SECONDS)) { proc.destroyForcibly(); -1 to "timeout" }
        else proc.exitValue() to out
    } catch (e: Exception) {
        -1 to (e.message ?: "su/cpuctl nedostupné")
    }

    /** `cpuctl version` → [CpuctlState]. Zavolá se před každým `refresh()`. */
    private fun cpuctlState(): CpuctlState {
        val (code, out) = cpuctl("version")
        if (code != 0) return CpuctlState.NoRoot
        return parseVersion(out)
    }

    /** `cpuctl apps 0` → [AppRow] přes [CpuTsv]-stylovou toleranci (vlastní formát `apps`, ne TSV z Tasku 1). */
    private fun parseApps(text: String): List<AppRow> =
        text.lineSequence().mapNotNull { line ->
            val f = line.split("\t")
            if (f.size < 4 || f[0] == "PKG") return@mapNotNull null
            val cpu = f[1].toDoubleOrNull() ?: return@mapNotNull null
            AppRow(f[0], cpu, f[2].toIntOrNull() ?: -1, f[3])
        }.toList()

    /**
     * Clustery ze sysfs: jádra seskupená podle cpuinfo_max_freq, vzestupně.
     * Jedno jádro bez cpufreq (offline) se vynechá. Jediný cluster =
     * homogenní SoC → "všechna", ne little/big. Vrací přímo [Cluster] z
     * `CpuData.kt`, aby MonitorTab/MonitorLogic nepracovaly s druhou
     * paralelní reprezentací.
     */
    private fun readClusters(): List<Cluster> {
        val byFreq = sortedMapOf<Long, MutableList<Int>>()
        for (core in 0 until 64) {
            val f = File("/sys/devices/system/cpu/cpu$core")
            if (!f.exists()) break
            val khz = runCatching {
                File(f, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull() ?: continue
            byFreq.getOrPut(khz) { mutableListOf() }.add(core)
        }
        if (byFreq.isEmpty()) return emptyList()
        val rows = byFreq.entries.flatMap { (khz, cores) ->
            cores.map { core -> CoreRow(core = core, maxKhz = khz, curKhz = khz, busyPromile = 0, cluster = byFreq.keys.indexOf(khz)) }
        }
        return CpuTsv.clusters(rows)
    }

    /**
     * Frekvenční **odhad** zátěže jader ze sysfs (`cur/max` poměr) — jen
     * fallback pro [MonitorLogic.coreLoad], když `su -c cpuctl cores`
     * selže (timeout, su nedostupné). Nejde o měřené zatížení: zamčené
     * jádro pod plnou zátěží vypadá stejně jako nečinné, boostnuté
     * nečinné vypadá jako zatížené. Caller musí tenhle rozdíl promítnout
     * do UI ([CoreLoad.estimated]), ne předstírat měření.
     */
    private fun readCoreRowsEstimate(clusters: List<Cluster>): List<CoreRow> {
        val clusterOfCore = clusters.flatMap { c -> c.cores.map { it to c.id } }.toMap()
        val rows = mutableListOf<CoreRow>()
        for (core in 0 until 64) {
            val dir = File("/sys/devices/system/cpu/cpu$core/cpufreq")
            if (!dir.exists()) break
            val maxKhz = runCatching { File(dir, "cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() ?: continue
            val curKhz = runCatching { File(dir, "scaling_cur_freq").readText().trim().toLong() }.getOrNull() ?: maxKhz
            val busy = if (maxKhz > 0) ((curKhz * 1000) / maxKhz).toInt().coerceIn(0, 1000) else 0
            rows += CoreRow(core, maxKhz, curKhz, busy, clusterOfCore[core] ?: 0)
        }
        return rows
    }

    /**
     * Systémová komponenta? Seznam z cpuctl obsahuje i balíčky s uid >= 10000,
     * které jsou součástí ROM (com.qti.*, org.codeaurora.*, system, …) — pinovat
     * je nemá smysl a tlačí uživatelské appky ze zorného pole.
     * Balíček, který PackageManager nezná (ořezané cmdline jako ".qtidataservices"),
     * taky není uživatelská appka → skryje se.
     */
    private fun isSystemPkg(pkg: String): Boolean {
        if (pkg == packageName) return false
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (e: Exception) {
            true
        }
    }

    @Composable
    private fun CpuAppsScreen() {
        val ctx = LocalContext.current
        var tab by remember { mutableStateOf(CpuTab.MONITOR) }
        var loading by remember { mutableStateOf(true) }
        var cpuState by remember { mutableStateOf<CpuctlState>(CpuctlState.NoRoot) }
        var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
        var systemPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
        var window by remember { mutableStateOf(MonitorWindow.FIVE_MIN) }
        var detailPkg by remember { mutableStateOf<String?>(null) }
        // Vzorky v paměti obrazovky, dokud `cpuctl history` neexistuje (Task 2).
        // Ořezáno na nejdelší nabízené okno (MonitorLogic.trimSamples), aby
        // periodické dovzorkování nerostlo bez stropu.
        val sampleHistory = remember { AppSampleBuffer() }
        val clusters = remember { readClusters() }
        var coreRows by remember { mutableStateOf<List<CoreRow>>(emptyList()) }
        var coreRowsEstimated by remember { mutableStateOf(false) }

        // Jedno kolo zjištění stavu + appek + jader. `showLoading=false` je
        // "tiché" kolo periodického tikeru — nesmí blikat spinnerem, jen
        // doplní vzorky na pozadí (Important nález revize Tasku 9).
        suspend fun fetchOnce(showLoading: Boolean) {
            if (showLoading) loading = true
            val state = withContext(Dispatchers.IO) { cpuctlState() }
            cpuState = state
            if (state !is CpuctlState.Ok) {
                rows = emptyList()
                systemPkgs = emptySet()
                if (showLoading) loading = false
                return
            }
            val (code, out) = withContext(Dispatchers.IO) { cpuctl("apps 0") }
            if (code != 0) {
                cpuState = CpuctlState.Unreadable(out.take(64))
                rows = emptyList()
                systemPkgs = emptySet()
            } else {
                val parsed = parseApps(out)
                systemPkgs = withContext(Dispatchers.IO) {
                    parsed.map { it.pkg }.filter { isSystemPkg(it) }.toSet()
                }
                rows = parsed
                val now = System.currentTimeMillis() / 1000
                for (row in parsed) {
                    sampleHistory.record(
                        row.pkg,
                        Sample(ts = now, promile = (row.cpu * 10).toInt(), core = 0, mask = row.mask),
                        nowTs = now,
                    )
                }
                // `cpuctl cores` (v1.4, garantováno ve stavu Ok) — fallback na
                // frekvenční odhad jen když samotné volání selže.
                val (coresCode, coresOut) = withContext(Dispatchers.IO) { cpuctl("cores") }
                val fallback = withContext(Dispatchers.IO) { readCoreRowsEstimate(clusters) }
                val load = MonitorLogic.coreLoad(if (coresCode == 0) coresOut else "", fallback)
                coreRows = load.rows
                coreRowsEstimated = load.estimated
            }
            if (showLoading) loading = false
        }

        fun refresh() {
            lifecycleScope.launch { fetchOnce(showLoading = true) }
        }

        fun pin(pkg: String, maskHex: String?) {
            lifecycleScope.launch {
                val arg = if (maskHex.isNullOrEmpty()) "off" else maskHex
                val (code, out) = withContext(Dispatchers.IO) { cpuctl("app-pin $pkg $arg") }
                // cpuctl vrací rc=0 i na "Usage:" (odmítnutý argument) — bez téhle
                // kontroly UI hlásilo úspěch, i když se nic nestalo.
                val failed = code != 0 || out.contains("Usage:")
                Toast.makeText(
                    ctx,
                    if (failed) "Selhalo: ${out.trim()}" else out.trim(),
                    Toast.LENGTH_SHORT,
                ).show()
                fetchOnce(showLoading = true)
            }
        }

        LaunchedEffect(Unit) { fetchOnce(showLoading = true) }

        // Periodický tiker: dovzorkovává appky/jádra, dokud je tab MONITOR
        // otevřený. Klíčovaný na `tab` → při odchodu z tabu (nebo zavření
        // obrazovky) se tahle coroutine automaticky zruší, nic neuteče na
        // pozadí. Žádné duplicitní první kolo — to už udělal LaunchedEffect
        // výše — proto se tu nejdřív čeká.
        LaunchedEffect(tab) {
            if (tab != CpuTab.MONITOR) return@LaunchedEffect
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                fetchOnce(showLoading = false)
            }
        }

        Box(Modifier.fillMaxSize().background(CpuColors.surface).padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "CPU aplikací",
                    color = CpuColors.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                TabRow(tab, onSelect = { tab = it })

                val detail = detailPkg
                when {
                    loading && rows.isEmpty() && cpuState is CpuctlState.NoRoot -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = CpuColors.ok)
                    }
                    detail != null && tab == CpuTab.MONITOR -> {
                        val appRow = rows.firstOrNull { it.pkg == detail }
                        AppDetail(
                            pkg = detail,
                            samples = sampleHistory.windowed(detail, window),
                            events = emptyList(), // cpuctl events je Task 2 — zatím žádná historie pinování.
                            clusters = clusters,
                            currentMask = appRow?.mask ?: "",
                            onPin = { maskHex -> pin(detail, maskHex) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            "zpět",
                            color = CpuColors.ok,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp).clickable { detailPkg = null },
                        )
                    }
                    tab == CpuTab.MONITOR -> MonitorTab(
                        screenState = MonitorLogic.screenStateFor(cpuState),
                        coreRows = coreRows,
                        coreRowsEstimated = coreRowsEstimated,
                        clusters = clusters,
                        apps = rows
                            .filter { it.pkg !in systemPkgs }
                            .map { MonitorAppRow(it.pkg, it.uid, it.mask, sampleHistory.windowed(it.pkg, window)) },
                        window = window,
                        onWindowChange = { window = it },
                        onOpenDetail = { detailPkg = it },
                        onRetry = { refresh() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    tab == CpuTab.CORES -> PreparingTab("Mapa jader")
                    else -> PreparingTab("Benchmark")
                }
            }
        }
    }

    @Composable
    private fun TabRow(current: CpuTab, onSelect: (CpuTab) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CpuTab.entries.forEach { t ->
                val active = t == current
                Text(
                    t.title,
                    color = if (active) CpuColors.ok else CpuColors.inkDim,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onSelect(t) }.padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }

    /** Task 10 vyplní — tady jen prázdný stav, aby obrazovka nebyla rozbitá tím, že tab existuje a nic nedělá. */
    @Composable
    private fun PreparingTab(name: String) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("$name — připravuje se.", color = CpuColors.inkDim, fontSize = 13.sp)
        }
    }
}

/**
 * Vzorky appek drženy v paměti obrazovky, dokud `cpuctl history` (Task 2)
 * neexistuje. Žije jen po dobu, co je `CpuAppsActivity` otevřená
 * (`remember { }`), ale s periodickým dovzorkováním ([SAMPLE_INTERVAL_MS])
 * by bez ořezu rostla bez stropu — [record] proto po každém zápisu ořeže
 * na nejdelší nabízené okno přes [MonitorLogic.trimSamples].
 */
private class AppSampleBuffer {
    private val byPkg = mutableMapOf<String, MutableList<Sample>>()

    /** [nowTs] dodává caller (sekundy), aby ořez šel přes testovatelné [MonitorLogic.trimSamples]. */
    fun record(pkg: String, sample: Sample, nowTs: Long) {
        val list = byPkg.getOrPut(pkg) { mutableListOf() }
        list.add(sample)
        val trimmed = MonitorLogic.trimSamples(list, nowTs, MonitorWindow.TWELVE_HOURS.seconds)
        byPkg[pkg] = trimmed.toMutableList()
    }

    /**
     * Vzorky z posledních [window] (podle `ts` v sekundách). Nikdy nevrací
     * víc, než kolik appka reálně má — okno 1h/12h bez dostatečně dlouhé
     * session prostě vrátí to málo, co je k dispozici; MonitorTab o tom
     * uživatele informuje stavem "žádná data", ne prázdným grafem.
     */
    fun windowed(pkg: String, window: MonitorWindow): List<Sample> {
        val samples = byPkg[pkg] ?: return emptyList()
        val nowTs = System.currentTimeMillis() / 1000
        return MonitorLogic.trimSamples(samples, nowTs, window.seconds)
    }
}
