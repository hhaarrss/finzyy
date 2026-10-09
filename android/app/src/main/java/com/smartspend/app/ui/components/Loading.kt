package com.smartspend.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartspend.app.ui.theme.FinzyyTheme
import kotlinx.coroutines.delay

/*
 * What the user sees while a screen's first load is in flight.
 *
 * The server sleeps when idle and can take up to a minute to answer the first request, so a
 * load is not always quick. Three things keep that wait from looking like a frozen or empty
 * app: placeholder cards in the shape of the real screen, a gold line that never stops moving,
 * and a sentence that changes as the wait gets longer.
 */

/** Elapsed time -> what to tell the user. Honest about the slow case instead of hiding it. */
private val LoadingSteps = listOf(
    0L to "Opening your ledger…",
    4_000L to "Fetching your latest payments…",
    10_000L to "Still working. The server is waking up, which can take up to a minute.",
)

@Composable
private fun rememberLoadingMessage(): String {
    var message by remember { mutableStateOf(LoadingSteps.first().second) }
    LaunchedEffect(Unit) {
        var elapsed = 0L
        for ((at, text) in LoadingSteps.drop(1)) {
            delay(at - elapsed)
            elapsed = at
            message = text
        }
    }
    return message
}

/** 0 -> 1, over and over. */
@Composable
private fun rememberSweep(periodMs: Int): State<Float> =
    rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

/** A thin track with a gold segment travelling along it: the app is working, not stuck. */
@Composable
fun LedgerProgressLine(modifier: Modifier = Modifier) {
    val sweep by rememberSweep(1500)
    val track = FinzyyTheme.colors.hairline
    val accent = FinzyyTheme.colors.accent
    Canvas(modifier.height(3.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val segment = size.width * 0.32f
        val x = -segment + (size.width + segment) * sweep
        clipRect {
            drawRoundRect(accent, topLeft = Offset(x, 0f), size = Size(segment, size.height), cornerRadius = r)
        }
    }
}

/** The changing sentence plus the moving line. */
@Composable
private fun LoadingNote(modifier: Modifier = Modifier, centered: Boolean = false) {
    val message = rememberLoadingMessage()
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Crossfade(targetState = message, animationSpec = tween(350), label = "loading-message") { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = FinzyyTheme.colors.inkMuted,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
        }
        Spacer(Modifier.height(10.dp))
        LedgerProgressLine(if (centered) Modifier.width(140.dp) else Modifier.fillMaxWidth())
    }
}

/**
 * Placeholder cards while a screen's first load is in flight. Heights mirror the real layout,
 * so nothing jumps when the data arrives. [note] adds the "what's happening" line on top; pass
 * false for a second skeleton on the same screen.
 */
@Composable
fun SkeletonBlocks(heights: List<Dp>, modifier: Modifier = Modifier, note: Boolean = true) {
    val sweep by rememberSweep(1300)
    Column(
        modifier = modifier.padding(horizontal = ScreenGutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (note) LoadingNote(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
        heights.forEach { h -> SkeletonCard(h, sweep) }
    }
}

/** One card-shaped placeholder: the card's own surface and edge, with shimmering lines inside. */
@Composable
private fun SkeletonCard(height: Dp, sweep: Float) {
    val base = FinzyyTheme.colors.hairline
    val glint = FinzyyTheme.colors.subtleSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, FinzyyTheme.colors.hairline, MaterialTheme.shapes.large)
            .padding(18.dp)
            .clearAndSetSemantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonLine(0.30f, 10.dp, sweep, base, glint)
        if (height >= 96.dp) SkeletonLine(0.55f, 24.dp, sweep, base, glint)
        if (height >= 150.dp) {
            Spacer(Modifier.height(4.dp))
            SkeletonLine(0.92f, 10.dp, sweep, base, glint)
            SkeletonLine(0.78f, 10.dp, sweep, base, glint)
        }
        if (height >= 240.dp) {
            SkeletonLine(0.86f, 10.dp, sweep, base, glint)
            SkeletonLine(0.48f, 10.dp, sweep, base, glint)
        }
    }
}

@Composable
private fun SkeletonLine(fraction: Float, height: Dp, sweep: Float, base: Color, glint: Color) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(CircleShape)
            .background(
                // A soft highlight that slides across each line, left to right.
                Brush.horizontalGradient(
                    colors = listOf(base, glint, base),
                    startX = -400f + 1600f * sweep,
                    endX = 1600f * sweep
                )
            )
    )
}

/**
 * Full-screen loader for the moments before any screen exists (deciding where a signed-in user
 * goes): the app's rupee mark, gently breathing, with the same sentence and line.
 */
@Composable
fun BrandLoader(modifier: Modifier = Modifier) {
    val breath by rememberInfiniteTransition(label = "mark").animateFloat(
        initialValue = 0.94f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breath"
    )
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RupeeTile(
                Modifier
                    .size(64.dp)
                    .graphicsLayer { scaleX = breath; scaleY = breath }
            )
            Spacer(Modifier.height(26.dp))
            LoadingNote(Modifier.widthIn(max = 260.dp), centered = true)
        }
    }
}

/** The launcher mark (rounded tile, rupee), in the theme's ink on the theme's ground. */
@Composable
private fun RupeeTile(modifier: Modifier = Modifier) {
    val tile = MaterialTheme.colorScheme.onBackground
    val glyph = MaterialTheme.colorScheme.background
    Canvas(modifier.clearAndSetSemantics { contentDescription = "Loading" }) {
        // Same numbers as ic_launcher_foreground.xml: the tile spans 30..78 of a 108 viewport,
        // and the rupee is scaled 0.82 about the centre (54, 54).
        val u = size.minDimension / 48f
        fun x(v: Float) = ((v - 54f) * 0.82f + 54f - 30f) * u
        fun y(v: Float) = ((v - 54f) * 0.82f + 54f - 30f) * u
        drawRoundRect(tile, cornerRadius = CornerRadius(14f * u, 14f * u))
        val rupee = Path().apply {
            moveTo(x(41f), y(36f)); lineTo(x(67f), y(36f))
            moveTo(x(41f), y(45.5f)); lineTo(x(67f), y(45.5f))
            moveTo(x(47f), y(36f))
            cubicTo(x(56.5f), y(36f), x(60f), y(40f), x(60f), y(45.5f))
            cubicTo(x(60f), y(51.5f), x(55.5f), y(55f), x(47.5f), y(55f))
            lineTo(x(42f), y(55f))
            lineTo(x(62f), y(72f))
        }
        drawPath(
            rupee, glyph,
            style = Stroke(width = 5.5f * 0.82f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
