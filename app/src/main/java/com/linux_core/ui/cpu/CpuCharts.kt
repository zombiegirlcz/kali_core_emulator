package com.linux_core.ui.cpu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linux_core.core.cpu.Cluster
import com.linux_core.core.cpu.CoreRow
import com.linux_core.core.cpu.PinEvent
import com.linux_core.core.cpu.Sample

/**
 * Sdílené Canvas grafy pro CPU trasování appek (Task 8, design viz
 * `docs/superpowers/specs/2026-10-22-cpu-apps-tracing-design.md`).
 *
 * Žádná grafová knihovna — vše kreslené ručně v `Canvas`. Barvy jsou dané
 * Global Constraints plánu ([CpuColors]), ne vkusem; rozlišení nikdy jen
 * odstínem (vždy i popisek/tvar/pozice navíc).
 */

/** Paleta ověřená v designu (dark surface `#1a1a19`, všech 6 kontrol prošlo). */
object CpuColors {
    val little = Color(0xFF3987E5)
    val big = Color(0xFFD95926)
    val ok = Color(0xFF199E70)
    val surface = Color(0xFF1A1A19)
    val ink = Color(0xFFF0EFEC)
    val inkDim = Color(0xFFC3C2B7)
}

/**
 * Rozsah osy Y pro `promile` hodnoty ze vzorků. Nikdy nedělí nulou:
 * - prázdný vstup → pevný rozsah `0f..1000f` (celá stupnice promile),
 * - všechny hodnoty stejné (vč. jediného vzorku) → rozsah kolem té hodnoty
 *   s minimální šířkou, aby se dalo bezpečně počítat `(v - lo) / (hi - lo)`.
 */
internal fun yScale(samples: List<Sample>): ClosedFloatingPointRange<Float> {
    if (samples.isEmpty()) return 0f..1000f

    var lo = samples[0].promile.toFloat()
    var hi = lo
    for (s in samples) {
        val v = s.promile.toFloat()
        if (v < lo) lo = v
        if (v > hi) hi = v
    }

    // Minimální rozpětí, aby dělení (hi - lo) nikdy nebylo nulové.
    val minSpan = 1f
    if (hi - lo < minSpan) {
        val mid = (hi + lo) / 2f
        lo = mid - minSpan / 2f
        hi = mid + minSpan / 2f
    }
    // Spodní hranice promile stupnice nikdy pod 0.
    if (lo < 0f) {
        hi -= lo
        lo = 0f
    }
    return lo..hi
}

/** Jednoduchá 2dp linka trendu bez osy — pro řádek appky v seznamu. */
@Composable
fun Sparkline(samples: List<Sample>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val range = yScale(samples)
        val span = range.endInclusive - range.start
        val stepX = size.width / (samples.size - 1).toFloat()

        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { index, sample ->
            val x = stepX * index
            val norm = (sample.promile.toFloat() - range.start) / span
            val y = size.height - (norm.coerceIn(0f, 1f) * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Graf s osou Y a popisky, svislými přerušovanými čárami pro [events] a
 * bodem + popiskem na maximu. Pro detail appky za zvolené okno.
 */
@Composable
fun TimelineChart(samples: List<Sample>, events: List<PinEvent>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CpuColors.inkDim, fontSize = 10.sp)

    Canvas(modifier = modifier.padding(start = 4.dp)) {
        val range = yScale(samples)
        val span = range.endInclusive - range.start

        // Osa Y: popisky min/max.
        val hiLabel = measurer.measure("${range.endInclusive.toInt()}‰", labelStyle)
        val loLabel = measurer.measure("${range.start.toInt()}‰", labelStyle)
        drawText(hiLabel, topLeft = Offset(0f, 0f))
        drawText(loLabel, topLeft = Offset(0f, size.height - loLabel.size.height))

        if (samples.isEmpty()) return@Canvas

        val chartLeft = maxOf(hiLabel.size.width, loLabel.size.width) + 6.dp.toPx()
        val chartWidth = (size.width - chartLeft).coerceAtLeast(1f)
        val tsMin = samples.minOf { it.ts }
        val tsMax = samples.maxOf { it.ts }
        val tsSpan = (tsMax - tsMin).coerceAtLeast(1L).toFloat()

        fun xOf(ts: Long): Float = chartLeft + ((ts - tsMin).toFloat() / tsSpan) * chartWidth
        fun yOf(promile: Int): Float {
            val norm = ((promile.toFloat() - range.start) / span).coerceIn(0f, 1f)
            return size.height - norm * size.height
        }

        // Svislé přerušované čáry pro pinovací události.
        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (event in events) {
            if (event.ts < tsMin || event.ts > tsMax) continue
            val x = xOf(event.ts)
            drawLine(
                color = CpuColors.inkDim,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashed,
            )
        }

        // Křivka samotná.
        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { index, sample ->
            val x = xOf(sample.ts)
            val y = yOf(sample.promile)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = CpuColors.ok, style = Stroke(width = 2.dp.toPx()))

        // Bod + popisek na maximu.
        val maxSample = samples.maxByOrNull { it.promile }
        if (maxSample != null) {
            val x = xOf(maxSample.ts)
            val y = yOf(maxSample.promile)
            drawCircle(color = CpuColors.ok, radius = 3.dp.toPx(), center = Offset(x, y))
            val maxLabel = measurer.measure("max ${maxSample.promile}‰", labelStyle)
            val labelX = (x - maxLabel.size.width / 2f).coerceIn(0f, size.width - maxLabel.size.width)
            val labelY = (y - maxLabel.size.height - 4.dp.toPx()).coerceAtLeast(0f)
            drawText(maxLabel, topLeft = Offset(labelX, labelY))
        }
    }
}

/** Najde cluster, do kterého patří dané jádro; `null`, pokud žádný. */
private fun clusterOf(core: Int, clusters: List<Cluster>): Cluster? =
    clusters.firstOrNull { core in it.cores }

/** Barva clusteru podle pozice (nejnižší frekvence = little, nejvyšší = big, mezi = OK/mid). */
private fun colorForCluster(cluster: Cluster?, clusters: List<Cluster>): Color {
    if (cluster == null || clusters.isEmpty()) return CpuColors.inkDim
    val sorted = clusters.sortedBy { it.maxKhz }
    val index = sorted.indexOfFirst { it.id == cluster.id }
    return when {
        sorted.size <= 1 -> CpuColors.ok
        index == 0 -> CpuColors.little
        index == sorted.size - 1 -> CpuColors.big
        else -> CpuColors.ok
    }
}

/** Bar per jádro, barva podle clusteru (little/big/mid), popisek čísla jádra. */
@Composable
fun CoreBars(rows: List<CoreRow>, clusters: List<Cluster>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CpuColors.ink, fontSize = 10.sp)

    Canvas(modifier = modifier) {
        if (rows.isEmpty()) return@Canvas
        val n = rows.size
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val labelHeight = 14.dp.toPx()
        val barAreaHeight = (size.height - labelHeight).coerceAtLeast(1f)

        rows.sortedBy { it.core }.forEachIndexed { index, row ->
            val x = index * (barWidth + gap)
            val busyFraction = (row.busyPromile.toFloat() / 1000f).coerceIn(0f, 1f)
            val barHeight = busyFraction * barAreaHeight
            val color = colorForCluster(clusterOf(row.core, clusters), clusters)

            drawRect(
                color = color,
                topLeft = Offset(x, barAreaHeight - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
            drawRect(
                color = color.copy(alpha = 0.25f),
                topLeft = Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(barWidth, barAreaHeight),
            )

            val label = measurer.measure("${row.core}", labelStyle)
            drawText(
                label,
                topLeft = Offset(
                    x + (barWidth - label.size.width) / 2f,
                    barAreaHeight + 2.dp.toPx(),
                ),
            )
        }
    }
}

/**
 * Dvojice horizontálních pruhů pro A/B benchmark (práce, odběr). Nikdy
 * nedělí nulou — když je součet hodnot 0, pruhy se jen nezobrazí (nulová
 * šířka), bez výjimky.
 */
@Composable
fun HBarPair(labelA: String, valueA: Double, labelB: String, valueB: Double, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = CpuColors.ink, fontSize = 12.sp)
    val max = maxOf(valueA, valueB, 0.0)

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barHeight = (size.height / 2f - 4.dp.toPx()).coerceAtLeast(1f)
            val maxBarWidth = size.width

            fun widthFor(value: Double): Float {
                if (max <= 0.0) return 0f
                return (value / max).toFloat().coerceIn(0f, 1f) * maxBarWidth
            }

            // Pruh A (little) nahoře, pruh B (big) dole — barva + popisek, ne jen odstín.
            drawRect(
                color = CpuColors.little,
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(widthFor(valueA), barHeight),
            )
            drawText(
                measurer.measure("$labelA: $valueA", labelStyle),
                topLeft = Offset(4.dp.toPx(), 2.dp.toPx()),
            )

            val secondTop = barHeight + 8.dp.toPx()
            drawRect(
                color = CpuColors.big,
                topLeft = Offset(0f, secondTop),
                size = androidx.compose.ui.geometry.Size(widthFor(valueB), barHeight),
            )
            drawText(
                measurer.measure("$labelB: $valueB", labelStyle),
                topLeft = Offset(4.dp.toPx(), secondTop + 2.dp.toPx()),
            )
        }
    }
}
