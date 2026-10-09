package com.smartspend.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspend.app.ui.theme.MonoFamily
import com.smartspend.app.ui.theme.FinzyyTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** [label] goes under the bar; [detail] is the full name shown when the bar is selected. */
data class ChartBar(val label: String, val value: Double, val detail: String = label)

/**
 * Single-series spend over time. One hue for every bar (bars are one measure, not categories);
 * the tapped bar keeps full strength and the rest step back, which is the only emphasis the
 * chart uses. Values aren't printed on bars — the selected bar's value is shown by the caller
 * above the chart, and the axis carries the rest.
 *
 * Marks follow the house chart spec: 4dp rounded data-end, square at the baseline, a surface
 * gap between neighbours, hairline solid gridlines one shade off the surface.
 */
@Composable
fun SpendBarChart(
    bars: List<ChartBar>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 180.dp,
    labelEvery: Int = 1,
    barColor: Color = MaterialTheme.colorScheme.primary,
    accessibilitySummary: String = ""
) {
    val grid = FinzyyTheme.colors.hairline
    val muted = FinzyyTheme.colors.inkMuted
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontFamily = MonoFamily, fontSize = 9.5.sp, color = muted, fontFeatureSettings = "tnum")
    val labelStyle = TextStyle(fontFamily = MonoFamily, fontSize = 9.5.sp, color = muted)

    val maxValue = bars.maxOfOrNull { it.value } ?: 0.0
    val axisMax = niceCeiling(maxValue)
    val grow = remember(bars) { Animatable(0f) }
    LaunchedEffect(bars) { grow.animateTo(1f, tween(450)) }

    // The right-hand axis strip and the bottom label band follow the text. They were a fixed
    // 40dp / 22dp while the labels are in sp, so at a large font size the labels were cut off.
    val density = LocalDensity.current
    val axisPx = with(density) {
        val widest = listOf(0.0, 0.5, 1.0).maxOf { measurer.measure(moneyShort(axisMax * it), tickStyle).size.width }
        maxOf(40.dp.toPx(), widest + 8.dp.toPx())
    }
    val xBand = with(density) {
        maxOf(22.dp, measurer.measure("Wg", labelStyle).size.height.toDp() + 10.dp)
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(plotHeight + xBand)
            .semantics { contentDescription = accessibilitySummary }
            .pointerInput(bars, selectedIndex, axisPx) {
                detectTapGestures { offset ->
                    val x = offset.x
                    val plotWidth = size.width - axisPx
                    if (bars.isEmpty() || x > plotWidth) return@detectTapGestures
                    val slot = plotWidth / bars.size
                    val index = (x / slot).toInt().coerceIn(0, bars.lastIndex)
                    onSelect(if (index == selectedIndex) null else index)
                }
            }
    ) {
        val axisWidth = axisPx
        val plotWidth = size.width - axisWidth
        val plotH = plotHeight.toPx()
        val hair = 1.dp.toPx()

        // Gridlines + y ticks at 0, ½, max.
        listOf(0.0, 0.5, 1.0).forEach { f ->
            val y = plotH - (plotH * f).toFloat()
            drawLine(grid, Offset(0f, y), Offset(plotWidth, y), hair)
            if (axisMax > 0) {
                val layout = measurer.measure(moneyShort(axisMax * f), tickStyle)
                drawText(layout, topLeft = Offset(plotWidth + 6.dp.toPx(), y - layout.size.height / 2f))
            }
        }

        if (bars.isEmpty() || axisMax <= 0) return@Canvas
        val slot = plotWidth / bars.size
        val gap = (slot * 0.28f).coerceIn(2.dp.toPx(), 10.dp.toPx())
        val barWidth = (slot - gap).coerceAtMost(28.dp.toPx())
        val radius = 4.dp.toPx().coerceAtMost(barWidth / 2)

        bars.forEachIndexed { i, bar ->
            val left = i * slot + (slot - barWidth) / 2f
            val h = ((bar.value / axisMax) * plotH * grow.value).toFloat()
            val dimmed = selectedIndex != null && selectedIndex != i
            if (h > 0.5f) {
                val top = plotH - h.coerceAtLeast(2.dp.toPx())
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = top, right = left + barWidth, bottom = plotH,
                            topLeftCornerRadius = CornerRadius(radius),
                            topRightCornerRadius = CornerRadius(radius),
                            bottomLeftCornerRadius = CornerRadius.Zero,
                            bottomRightCornerRadius = CornerRadius.Zero
                        )
                    )
                }
                drawPath(path, barColor.copy(alpha = if (dimmed) 0.3f else 1f))
            }
            val showLabel = i % labelEvery == 0 || i == selectedIndex
            if (showLabel && bar.label.isNotEmpty()) {
                val layout = measurer.measure(bar.label, labelStyle)
                val x = (left + barWidth / 2f - layout.size.width / 2f)
                    .coerceIn(0f, plotWidth - layout.size.width)
                drawText(layout, topLeft = Offset(x, plotH + 6.dp.toPx()))
            }
        }
    }
}

/** Round up to 1/2/2.5/5 × 10ⁿ so the top tick is a number people would write. */
private fun niceCeiling(value: Double): Double {
    if (value <= 0) return 0.0
    val exp = floor(log10(value))
    val base = 10.0.pow(exp)
    val f = value / base
    val nice = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= f - 1e-9 }
    return ceil(nice * base)
}

data class ShareSlice(val label: String, val value: Double, val color: Color)

/**
 * Part-to-whole as one engraved band: shades of ink from darkest (biggest) to lightest, split
 * by a 2dp gap. Callers pass at most five slices (top four + "Everything else"). The rows
 * beneath it carry each slice's name and value, so no shade has to be read on its own.
 */
@Composable
fun ShareStrip(slices: List<ShareSlice>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return
    val grow = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { grow.animateTo(1f, tween(600)) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val gap = 2.dp.toPx()
        val usable = (size.width - gap * (slices.size - 1)) * grow.value
        var x = 0f
        slices.forEach { slice ->
            val w = (slice.value / total * usable).toFloat()
            drawRoundRect(slice.color, Offset(x, 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
            x += w + gap
        }
    }
}

/**
 * Fold everything past the top [keep] into one "Everything else" slice and shade by rank,
 * darkest first, from [shades].
 */
fun <T> foldToShares(
    items: List<T>,
    value: (T) -> Double,
    label: (T) -> String,
    shades: List<Color>,
    keep: Int = 4
): List<ShareSlice> {
    val sorted = items.filter { value(it) > 0 }.sortedByDescending(value)
    val head = sorted.take(keep).mapIndexed { i, it -> ShareSlice(label(it), value(it), shades[i.coerceAtMost(shades.lastIndex)]) }
    val rest = sorted.drop(keep).sumOf(value)
    return if (rest > 0) head + ShareSlice("Everything else", rest, shades.last()) else head
}

/**
 * Guilloché: interlaced hypotrochoid bands — the security-print line work on banknotes and
 * cheques. Drawn once per size in fine gold, fading out toward [fadeTo] on the left so the
 * figures printed over it stay legible. Used on exactly one surface per screen.
 */
@Composable
fun Guilloche(
    color: Color,
    fadeTo: Color,
    modifier: Modifier = Modifier,
    centerX: Float = 1.04f,
    centerY: Float = 0.46f,
    strength: Float = 1f
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1400)) }
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width * centerX
        val cy = size.height * centerY
        val unit = size.height / 190f
        val bands = listOf(
            floatArrayOf(150f, 23f, 118f, 0.26f),
            floatArrayOf(120f, 17f, 98f, 0.18f),
            floatArrayOf(190f, 29f, 150f, 0.12f)
        )
        bands.forEach { (bigR, smallR, d, alpha) ->
            val path = androidx.compose.ui.graphics.Path()
            val k = (bigR - smallR) / smallR
            val end = (2 * Math.PI * smallR).toFloat()
            var t = 0f
            var first = true
            while (t <= end) {
                val x = cx + ((bigR - smallR) * kotlin.math.cos(t) + d * kotlin.math.cos(k * t)) * unit
                val y = cy + ((bigR - smallR) * kotlin.math.sin(t) - d * kotlin.math.sin(k * t)) * unit
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                t += 0.02f
            }
            drawPath(path, color.copy(alpha = alpha * strength * reveal.value), style = Stroke(width = 0.6.dp.toPx()))
        }
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to fadeTo,
                0.4f to fadeTo.copy(alpha = 0.85f),
                0.72f to fadeTo.copy(alpha = 0f),
                startX = 0f,
                endX = size.width
            )
        )
    }
}

@Composable
fun LegendSwatch(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(10.dp)) {
        drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx()))
    }
}

