package com.linux_core.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Přehled CPU zátěže ostatních aplikací + přišpendlení na jádra.
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
 */
class CpuAppsActivity : ComponentActivity() {

    data class AppRow(val pkg: String, val cpu: Double, val uid: Int, val mask: String)

    /** Skupina jader se stejnou maximální frekvencí. */
    data class CpuCluster(val label: String, val cores: List<Int>, val maxMhz: Int) {
        val maskHex: String
            get() = java.lang.Long.toHexString(cores.fold(0L) { acc, c -> acc or (1L shl c) })

        /** "0–5" nebo "6, 7" podle spojitosti. */
        val coresLabel: String
            get() = if (cores.size > 1 && cores.last() - cores.first() == cores.size - 1) {
                "${cores.first()}–${cores.last()}"
            } else {
                cores.joinToString(", ")
            }
    }

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
     * homogenní SoC → "všechna jádra", ne little/big.
     */
    private fun readClusters(): List<CpuCluster> {
        val byFreq = sortedMapOf<Int, MutableList<Int>>()
        for (core in 0 until 64) {
            val f = File("/sys/devices/system/cpu/cpu$core")
            if (!f.exists()) break
            val khz = runCatching {
                File(f, "cpufreq/cpuinfo_max_freq").readText().trim().toInt()
            }.getOrNull() ?: continue
            byFreq.getOrPut(khz) { mutableListOf() }.add(core)
        }
        if (byFreq.isEmpty()) return emptyList()
        val groups = byFreq.entries.toList()
        return groups.mapIndexed { idx, (khz, cores) ->
            val label = when {
                groups.size == 1 -> "všechna jádra"
                idx == 0 -> "little"
                idx == groups.size - 1 -> "big"
                groups.size == 3 -> "mid"
                else -> "c$idx"
            }
            CpuCluster(label, cores.sorted(), khz / 1000)
        }
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
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
        var showSystem by remember { mutableStateOf(false) }
        var systemPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
        val clusters = remember { readClusters() }

        // PackageManager dotazy patří na IO vlákno, ne do composition: pro ~15
        // balíčků na main threadu by to bylo viditelné zaškubnutí při každé
        // rekompozici (a filtr se přepočítává i při zaškrtnutí checkboxu).
        fun refresh() {
            loading = true
            lifecycleScope.launch {
                val (code, out) = withContext(Dispatchers.IO) { cpuctl("apps 0") }
                if (code != 0) {
                    error = "Root CPU daemon (Magisk nh_cpuctl) nedostupný.\n${out.trim()}"
                    rows = emptyList()
                    systemPkgs = emptySet()
                } else {
                    error = null
                    val parsed = parseApps(out)
                    systemPkgs = withContext(Dispatchers.IO) {
                        parsed.map { it.pkg }.filter { isSystemPkg(it) }.toSet()
                    }
                    rows = parsed
                }
                loading = false
            }
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
                refresh()
            }
        }

        LaunchedEffect(Unit) { refresh() }

        val visible = rows.filter { showSystem || it.pkg !in systemPkgs }
            .sortedByDescending { it.cpu }
        val hiddenCount = rows.count { it.pkg in systemPkgs }

        Box(Modifier.fillMaxSize().background(Color(0xFF08090D)).padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "CPU aplikací",
                    color = Color(0xFF00FF41),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (clusters.size > 1) {
                        "Klepni na jádra pro přišpendlení. " +
                            clusters.joinToString(" · ") { "${it.label} ${it.coresLabel} @${it.maxMhz} MHz" }
                    } else {
                        "Klepni na jádra pro přišpendlení aplikace. vše = všechna jádra."
                    },
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = showSystem,
                        onCheckedChange = { showSystem = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF00D2FF),
                            uncheckedColor = Color.Gray,
                        ),
                    )
                    Text(
                        if (showSystem) "systémové zobrazeny" else "zobrazit systémové ($hiddenCount)",
                        color = Color.Gray,
                        fontSize = 11.sp,
                    )
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00FF41))
                    }
                    error != null -> Text(error!!, color = Color(0xFFFF5252), fontSize = 13.sp)
                    visible.isEmpty() -> Text(
                        if (rows.isEmpty()) "Žádné aplikace k zobrazení."
                        else "Jen systémové komponenty ($hiddenCount) — zaškrtni výše.",
                        color = Color.Gray,
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(visible) { row ->
                            AppCard(row, clusters) { pkg, mask -> pin(pkg, mask) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppCard(
        row: AppRow,
        clusters: List<CpuCluster>,
        onPin: (String, String?) -> Unit,
    ) {
        val heat = when {
            row.cpu >= 50 -> Color(0xFFFF5252)
            row.cpu >= 15 -> Color(0xFFFFB300)
            else -> Color(0xFF00FF41)
        }
        val allMask = clusters.flatMap { it.cores }
            .fold(0L) { acc, c -> acc or (1L shl c) }
            .let { java.lang.Long.toHexString(it) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12131A)),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.pkg,
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(8.dp).background(heat, RoundedCornerShape(4.dp)))
                    Text(
                        "  %.1f%%".format(row.cpu),
                        color = heat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    "uid ${row.uid} · jádra ${describeMask(row.mask, clusters, allMask)}",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (clusters.size > 1) {
                        clusters.forEach { c ->
                            CoreChip("${c.label} ${c.coresLabel}", row.mask == c.maskHex) {
                                onPin(row.pkg, c.maskHex)
                            }
                        }
                    }
                    CoreChip("vše", row.mask == allMask) { onPin(row.pkg, null) }
                }
            }
        }
    }

    /** "ff" → "všechna (ff)", "c0" → "big 6–7 (c0)", jinak holý hex. */
    private fun describeMask(
        mask: String,
        clusters: List<CpuCluster>,
        allMask: String,
    ): String = when {
        mask == allMask -> "všechna ($mask)"
        else -> clusters.firstOrNull { it.maskHex == mask }
            ?.let { "${it.label} ${it.coresLabel} ($mask)" }
            ?: mask
    }

    @Composable
    private fun CoreChip(label: String, active: Boolean, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            color = if (active) Color(0xFF123A4A) else Color(0xFF1E2026),
        ) {
            Text(
                label,
                color = if (active) Color(0xFF7FE9FF) else Color(0xFF00D2FF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
