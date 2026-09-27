package com.linux_core.ui

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
import java.util.concurrent.TimeUnit

/**
 * Přehled CPU zátěže ostatních aplikací + přišpendlení na jádra.
 *
 * Řízení cizích procesů vyžaduje root — mluví přímo s /system/bin/cpuctl
 * (Magisk modul nh_cpuctl) přes `su -c`, stejný vzor jako runCpuBoost.
 * Pin i čtení jsou omezené na aplikace (uid >= 10000) přímo v cpuctl.c;
 * systémové procesy se nikdy nepinují.
 */
class CpuAppsActivity : ComponentActivity() {

    data class AppRow(val pkg: String, val cpu: Double, val uid: Int, val mask: String)

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

    @Composable
    private fun CpuAppsScreen() {
        val ctx = LocalContext.current
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }

        fun refresh() {
            loading = true
            lifecycleScope.launch {
                val (code, out) = withContext(Dispatchers.IO) { cpuctl("apps 0") }
                if (code != 0) {
                    error = "Root CPU daemon (Magisk nh_cpuctl) nedostupný.\n${out.trim()}"
                    rows = emptyList()
                } else {
                    error = null
                    rows = parseApps(out)
                }
                loading = false
            }
        }

        fun pin(pkg: String, cores: String?) {
            lifecycleScope.launch {
                val arg = if (cores.isNullOrEmpty()) "off" else cores
                val (code, out) = withContext(Dispatchers.IO) { cpuctl("app-pin $pkg $arg") }
                Toast.makeText(
                    ctx,
                    if (code == 0) out.trim() else "Selhalo: ${out.trim()}",
                    Toast.LENGTH_SHORT,
                ).show()
                refresh()
            }
        }

        LaunchedEffect(Unit) { refresh() }

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
                    "Klepni na jádra pro přišpendlení aplikace. off = všechna jádra.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00FF41))
                    }
                    error != null -> Text(error!!, color = Color(0xFFFF5252), fontSize = 13.sp)
                    rows.isEmpty() -> Text("Žádné aplikace k zobrazení.", color = Color.Gray)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows) { row -> AppCard(row) { pkg, cores -> pin(pkg, cores) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppCard(row: AppRow, onPin: (String, String?) -> Unit) {
        val heat = when {
            row.cpu >= 50 -> Color(0xFFFF5252)
            row.cpu >= 15 -> Color(0xFFFFB300)
            else -> Color(0xFF00FF41)
        }
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
                    "uid ${row.uid} · jádra ${row.mask}",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CoreChip("little (0-3)") { onPin(row.pkg, "0-3") }
                    CoreChip("big (4-7)") { onPin(row.pkg, "4-7") }
                    CoreChip("vše (off)") { onPin(row.pkg, null) }
                }
            }
        }
    }

    @Composable
    private fun CoreChip(label: String, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF1E2026),
        ) {
            Text(
                label,
                color = Color(0xFF00D2FF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
