package com.linux_core.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Správa Shizuku oprávnění: appky s `rikka.shizuku:provider` (authority
 * `<balíček>.shizuku`) + všechny, co už grant mají. Přepínač zapisuje do
 * stejných prefs jako `nh shizuku grant/revoke` (`shizuku_permissions`,
 * čte je server přes `/shizuku/permission*`, obnova do ~5 s).
 */
class ShizukuAppsActivity : ComponentActivity() {

    data class AppRow(val pkg: String, val label: String, val granted: Boolean, val hasProvider: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShizukuAppsScreen() }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadApps(): List<AppRow> {
        val pm = packageManager
        val grants = prefs().all.mapValues { it.value as? Boolean ?: false }
        @Suppress("DEPRECATION")
        val withProvider =
            pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
                .filter { p -> p.providers?.any { it.authority?.split(';')?.contains("${p.packageName}.shizuku") == true } == true }
                .map { it.packageName }
                .toSet() - packageName
        return (withProvider + grants.keys).map { pkg ->
            val label =
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    "(nenainstalováno)"
                }
            AppRow(pkg, label, grants[pkg] ?: false, pkg in withProvider)
        }.sortedWith(compareByDescending<AppRow> { it.granted }.thenBy { it.label.lowercase() })
    }

    @Composable
    private fun ShizukuAppsScreen() {
        var loading by remember { mutableStateOf(true) }
        var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }

        fun refresh() {
            lifecycleScope.launch {
                rows = withContext(Dispatchers.IO) { loadApps() }
                loading = false
            }
        }

        fun setGranted(pkg: String, granted: Boolean) {
            prefs().edit().putBoolean(pkg, granted).apply()
            refresh()
        }

        LaunchedEffect(Unit) { refresh() }

        Box(Modifier.fillMaxSize().background(Color(0xFF08090D)).padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Shizuku aplikace",
                    color = Color(0xFF00FF41),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Přepínač = grant/revoke (stejné jako nh shizuku grant). Běžící appka dostane binder do ~5 s.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00FF41))
                    }
                    rows.isEmpty() -> Text("Žádná appka s Shizuku API nenalezena.", color = Color.Gray)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(rows, key = { it.pkg }) { row -> AppCard(row) { setGranted(row.pkg, it) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppCard(row: AppRow, onToggle: (Boolean) -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12131A)),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.label,
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        row.pkg + if (row.hasProvider) "" else "  · bez Shizuku provideru",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Switch(
                    checked = row.granted,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00FF41)),
                )
            }
        }
    }

    private companion object {
        const val PREFS = "shizuku_permissions"
    }
}
