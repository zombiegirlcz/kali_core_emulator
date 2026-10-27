package com.linux_core.ui.cpu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.cpu.Cluster
import com.linux_core.core.cpu.CoreRow
import com.linux_core.core.cpu.CpuTsv
import com.linux_core.core.cpu.CpuctlState
import com.linux_core.core.cpu.PinEvent
import com.linux_core.core.cpu.Sample

/**
 * Tab Monitor (Task 9, design `docs/superpowers/specs/2026-10-22-cpu-apps-tracing-design.md`).
 *
 * Historie `cpuctl history` zatím neexistuje (Task 2 ještě neproběhl) — obrazovka
 * (`CpuAppsActivity`) si sama drží vzorky v paměti a periodicky je dovzorkovává,
 * dokud je otevřená a tab MONITOR aktivní. Jakmile Task 2 dodá `cpuctl history`,
 * zdroj vzorků se vymění, stavová logika ([MonitorLogic]) se nemění — dostává
 * hotový `List<Sample>`, ne zdroj dat samotný.
 */

/** Okno zobrazené historie. Zatím jen informativní popisek — data za delší
 * okno než "živá session" nejsou k dispozici (viz [MonitorScreenState]). */
enum class MonitorWindow(val label: String, val seconds: Long) {
    FIVE_MIN("5 min", 5 * 60L),
    ONE_HOUR("1 h", 60 * 60L),
    TWELVE_HOURS("12 h", 12 * 60 * 60L),
}

/** Chip pro přišpendlení appky na jádra daného clusteru (nebo "vše"). */
data class PinChip(val label: String, val maskHex: String)

/** Výsledný stav obrazovky odvozený z [CpuctlState]. */
sealed interface MonitorScreenState {
    /** Normální provoz — `cpuctl` je dost nová a root je k dispozici. */
    object Ready : MonitorScreenState

    /**
     * Obrazovka nemůže měřit. [message] vysvětluje proč (nikdy nekreslit
     * prázdný graf namísto téhle zprávy). [canRetry] rozlišuje dočasnou
     * chybu ([CpuctlState.Unreadable], zkrácený/zašuměný výstup `su`) od
     * trvalé (chybějící root/modul, stará binárka) — jen dočasná nabízí
     * tlačítko "zkusit znovu".
     */
    data class Blocked(val message: String, val canRetry: Boolean) : MonitorScreenState
}

/**
 * Čistá stavová logika tabu Monitor — testovatelná na JVM bez Compose
 * runtime (viz `MonitorStateTest.kt`).
 */
internal object MonitorLogic {

    /** Kolik po sobě jdoucích (posledních) vzorků nad prahem znamená "drží to". */
    private const val SUSTAINED_SAMPLES = 3

    /**
     * Appka "drží nad prahem", pokud posledních [SUSTAINED_SAMPLES] vzorků
     * (v pořadí, jak přišly) je *všechny* nad [thresholdPromile]. Jediný
     * krátký výkyv uprostřed historie nesmí spustit trvalé varování —
     * proto se dívá jen na poslední souvislý úsek, ne na "bylo to nad
     * prahem kdykoliv".
     */
    fun isOverThreshold(samples: List<Sample>, thresholdPromile: Int): Boolean {
        if (samples.size < SUSTAINED_SAMPLES) return false
        val tail = samples.takeLast(SUSTAINED_SAMPLES)
        return tail.all { it.promile > thresholdPromile }
    }

    /** Mapuje [CpuctlState] na stav obrazovky — viz prázdné/chybové stavy v briefu. */
    fun screenStateFor(state: CpuctlState): MonitorScreenState = when (state) {
        is CpuctlState.Ok -> MonitorScreenState.Ready
        is CpuctlState.NoRoot -> MonitorScreenState.Blocked(
            message = "Pinování vyžaduje modul nh_cpuctl (root). Bez něj Monitor jen ukazuje, že data nejsou.",
            canRetry = false,
        )
        is CpuctlState.TooOld -> MonitorScreenState.Blocked(
            message = "cpuctl ${state.version} neumí history — přeflashuj nh_cpuctl 1.4.",
            canRetry = false,
        )
        is CpuctlState.Unreadable -> MonitorScreenState.Blocked(
            message = "Odpověď cpuctl se nepodařilo přečíst (zkrácený výstup su): \"${state.raw}\".",
            canRetry = true,
        )
    }

    /**
     * Pinovací chipy odvozené **z clusterů**, ne z natvrdo dané konstanty
     * (dřívější špatné `0-3`/`4-7` — reálné zařízení má 0-5 @1804 MHz a
     * 6-7 @2304 MHz, viz AGENTS.md). Jeden cluster (homogenní SoC) → jen
     * jeden chip "vše" na všechna jádra toho clusteru. Víc clusterů →
     * jeden chip na cluster + navíc "vše" na všechna jádra souhrnně.
     */
    fun pinChips(clusters: List<Cluster>): List<PinChip> {
        if (clusters.isEmpty()) return emptyList()
        if (clusters.size == 1) {
            val only = clusters[0]
            return listOf(PinChip(only.label, CpuTsv.maskOf(only.cores)))
        }
        val perCluster = clusters.map { PinChip(it.label, CpuTsv.maskOf(it.cores)) }
        val allCores = clusters.flatMap { it.cores }
        return perCluster + PinChip("vše", CpuTsv.maskOf(allCores))
    }

    /**
     * Ořeže vzorky appky na nejdelší nabízené okno ([MonitorWindow.TWELVE_HOURS]),
     * aby [AppSampleBuffer] (v `CpuAppsActivity`) nerostl bez stropu, jakmile
     * přibyde periodické vzorkování. `nowTs` dostává caller (testovatelnost,
     * žádné `System.currentTimeMillis()` v čisté logice).
     */
    fun trimSamples(samples: List<Sample>, nowTs: Long, maxWindowSeconds: Long): List<Sample> {
        val cutoff = nowTs - maxWindowSeconds
        return samples.filter { it.ts >= cutoff }
    }

    /**
     * Zátěž jader pro pás nahoře. `cpuctl cores` je od Tasku 1 (v1.4)
     * garantovaně dostupný ve stavu [MonitorScreenState.Ready] (`parseVersion`
     * to vyžaduje), ale samotné volání `su -c cpuctl cores` může selhat
     * (timeout, su nedostupné) — tehdy spadne na frekvenční odhad
     * (`estimatedFallback`, dodá caller z `cpuinfo_max_freq`/`scaling_cur_freq`).
     * [CoreLoad.estimated] říká UI, že nejde o měřené zatížení — frekvenční
     * poměr zavádí v obou směrech (zamčené jádro pod zátěží vs. boostnuté
     * nečinné), takže se to nesmí tvářit jako měření.
     */
    fun coreLoad(coresOutput: String, estimatedFallback: List<CoreRow>): CoreLoad {
        val measured = CpuTsv.cores(coresOutput)
        return if (measured.isNotEmpty()) CoreLoad(measured, estimated = false)
        else CoreLoad(estimatedFallback, estimated = true)
    }
}

/** Výsledek [MonitorLogic.coreLoad] — řádky k vykreslení + zda jde o odhad. */
data class CoreLoad(val rows: List<CoreRow>, val estimated: Boolean)

/** Jedna appka na obrazovce Monitor — agregát, který aktivita udržuje v paměti. */
data class MonitorAppRow(
    val pkg: String,
    val uid: Int,
    val mask: String,
    val samples: List<Sample>,
    val events: List<PinEvent> = emptyList(),
)

/**
 * Tab Monitor: pás jader nahoře, seznam appek se sparkline a teď/max,
 * přepínač okna, výstražný řádek nad prahem.
 *
 * [screenState] řídí prázdné/chybové stavy — `Blocked` nikdy nekreslí
 * sparkliny, jen vysvětlení a případně tlačítko na opakování.
 */
@Composable
fun MonitorTab(
    screenState: MonitorScreenState,
    coreRows: List<CoreRow>,
    coreRowsEstimated: Boolean,
    clusters: List<Cluster>,
    apps: List<MonitorAppRow>,
    window: MonitorWindow,
    onWindowChange: (MonitorWindow) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRetry: () -> Unit,
    thresholdPromile: Int = 500,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        when (screenState) {
            is MonitorScreenState.Blocked -> BlockedState(screenState, onRetry)
            MonitorScreenState.Ready -> {
                if (coreRows.isNotEmpty()) {
                    CoreBars(coreRows, clusters, modifier = Modifier.fillMaxWidth().height(72.dp))
                    if (coreRowsEstimated) {
                        // Frekvenční odhad zavádí v obou směrech (viz MonitorLogic.coreLoad) —
                        // nikdy se nesmí tvářit jako měřené zatížení.
                        Text(
                            "odhad podle frekvence — cpuctl cores nedostupný",
                            color = CpuColors.inkDim,
                            fontSize = 10.sp,
                        )
                    }
                }
                WindowSwitch(window, onWindowChange)
                if (apps.isEmpty()) {
                    Text(
                        "Žádná appka zatím nezměřena — vzorkování běží, dokud je tahle obrazovka otevřená.",
                        color = CpuColors.inkDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    val sorted = apps.sortedByDescending { it.samples.lastOrNull()?.promile ?: 0 }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        items(sorted) { app ->
                            MonitorAppCard(app, thresholdPromile, onClick = { onOpenDetail(app.pkg) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedState(state: MonitorScreenState.Blocked, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.message, color = CpuColors.inkDim, fontSize = 13.sp)
            if (state.canRetry) {
                Text(
                    "zkusit znovu",
                    color = CpuColors.ok,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).clickable { onRetry() },
                )
            }
        }
    }
}

@Composable
private fun WindowSwitch(current: MonitorWindow, onChange: (MonitorWindow) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MonitorWindow.entries.forEach { w ->
            val active = w == current
            Text(
                w.label,
                color = if (active) CpuColors.ok else CpuColors.inkDim,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (active) CpuColors.surface else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable { onChange(w) },
            )
        }
    }
}

@Composable
private fun MonitorAppCard(app: MonitorAppRow, thresholdPromile: Int, onClick: () -> Unit) {
    val warning = MonitorLogic.isOverThreshold(app.samples, thresholdPromile)
    val now = app.samples.lastOrNull()?.promile ?: 0
    val max = app.samples.maxOfOrNull { it.promile } ?: 0

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CpuColors.surface),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.pkg,
                    color = CpuColors.ink,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "teď ${now / 10}.${now % 10}% · max ${max / 10}.${max % 10}%",
                    color = CpuColors.inkDim,
                    fontSize = 11.sp,
                )
            }
            if (app.samples.size >= 2) {
                Sparkline(
                    app.samples,
                    color = if (warning) CpuColors.big else CpuColors.ok,
                    modifier = Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp),
                )
            }
            if (warning) {
                // Barva samotná nestačí (Global Constraints) — text říká přímo,
                // proč je appka vypsaná touto barvou.
                Text(
                    "⚠ nad prahem déle než pár minut",
                    color = CpuColors.big,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Detail appky: graf za zvolené okno s `events`, pinovací chipy z clusterů
 * a textová historie.
 *
 * Žádný přepínač "jednorázově vs. trvale" — `cpuctl app-pin` (`cpuctl.c`,
 * `cmd_app_pin`) zapisuje pravidlo `app.<pkg>` **vždy**, bez ohledu na
 * argumenty; jednorázový pin by šel jen přes `cpuctl pin <mask> <pid>`, ale
 * `cpuctl apps` ve výstupu PID nemá, takže to bez změny C kódu nejde
 * implementovat. Přepínač, který by nic neovlivňoval, by UI jen lhalo.
 */
@Composable
fun AppDetail(
    pkg: String,
    samples: List<Sample>,
    events: List<PinEvent>,
    clusters: List<Cluster>,
    currentMask: String,
    onPin: (maskHex: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = MonitorLogic.pinChips(clusters)

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(pkg, color = CpuColors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

        if (samples.isEmpty()) {
            Text(
                "Zatím žádná data pro tuhle appku — vzorkování běží jen, dokud je obrazovka Monitor otevřená.",
                color = CpuColors.inkDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            TimelineChart(samples, events, modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp))
        }

        Text(
            "Přišpendlit na (zapisuje trvalé pravidlo app.$pkg, přežije restart appky):",
            color = CpuColors.inkDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { chip ->
                val active = chip.maskHex == currentMask
                Text(
                    chip.label,
                    color = if (active) CpuColors.ok else CpuColors.inkDim,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(if (active) CpuColors.surface else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable { onPin(chip.maskHex) },
                )
            }
        }

        if (events.isNotEmpty()) {
            Text("Historie pinování:", color = CpuColors.inkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            LazyColumn(modifier = Modifier.padding(top = 4.dp)) {
                items(events.sortedByDescending { it.ts }) { e ->
                    Text(
                        "${e.ts}: ${e.oldMask} → ${e.newMask} (${e.who})",
                        color = CpuColors.inkDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
