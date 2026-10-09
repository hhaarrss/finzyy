package com.smartspend.app.ui.tour

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** One stop on a tour: the element to spotlight and what to say about it. */
data class TourStep(val targetId: String, val title: String, val body: String)

/**
 * Where each tagged element currently sits on screen, in root coordinates. Elements register
 * themselves through [tourTarget]; the overlay reads the one for the current step.
 */
@Stable
class TourController {
    internal val targets = mutableStateMapOf<String, Rect>()
}

val LocalTourController = staticCompositionLocalOf<TourController?> { null }

/** Tags an element as a tour stop. Costs nothing when no tour is running. */
fun Modifier.tourTarget(id: String): Modifier = composed {
    val controller = LocalTourController.current
    if (controller == null) this
    else this.onGloballyPositioned { controller.targets[id] = it.boundsInRoot() }
}

/**
 * The coach-mark layer, drawn over the live screen: a dim scrim with a rounded cut-out that
 * glides from one real element to the next, a thin gold ring breathing around it, and a
 * tooltip card with a pointer. The screen underneath can't be tapped while it's up — only
 * Next / Skip (and system back, which skips) move the tour along.
 *
 * @param listState when given, off-screen targets are scrolled into view before they're shown.
 */
@Composable
fun TourOverlay(
    controller: TourController,
    steps: List<TourStep>,
    onFinish: () -> Unit,
    listState: LazyListState? = null
) {
    var index by remember { mutableIntStateOf(0) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    val fadeIn = remember { Animatable(0f) }
    val density = LocalDensity.current

    val step = steps[index]
    val pad = with(density) { 8.dp.toPx() }
    val spot = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var ready by remember { mutableStateOf(false) }

    BackHandler(onBack = onFinish)

    // Fade in on its own, once. It used to run inside the step effect below, and a late resize
    // restarted that effect mid-fade — leaving the whole tour stuck invisible.
    LaunchedEffect(ready) {
        if (ready) fadeIn.animateTo(1f, tween(260))
    }

    // Bring the step's target into view, then glide the spotlight onto it. Keyed on the step
    // only; a resize must not restart it.
    LaunchedEffect(index) {
        snapshotFlow { overlaySize }.first { it != IntSize.Zero }
        val id = step.targetId
        if (listState != null) {
            val margin = with(density) { 160.dp.toPx() }
            val topClearance = with(density) { 90.dp.toPx() }
            for (attempt in 0 until 6) {
                val r = controller.targets[id]
                val visibleBottom = overlayOrigin.y + overlaySize.height - margin
                when {
                    r == null -> listState.animateScrollBy(overlaySize.height * 0.5f)
                    r.bottom > visibleBottom -> listState.animateScrollBy(r.bottom - visibleBottom)
                    r.top < overlayOrigin.y + topClearance && listState.canScrollBackward ->
                        listState.animateScrollBy(r.top - overlayOrigin.y - topClearance)
                    else -> break // in view
                }
                delay(60)
            }
        }
        // Wait for a laid-out rect (a target can take a frame to report after scrolling).
        val target = withTimeoutOrNull(1500) {
            snapshotFlow { controller.targets[id] }.first { it != null && it.width > 0f }
        } ?: controller.targets[id]
        if (target == null) {
            // Nothing to point at (e.g. a section hidden for this user): skip the stop.
            Log.w("HomeTour", "step ${index + 1} '$id': target never laid out — skipping (known: ${controller.targets.keys})")
            if (index < steps.lastIndex) index++ else onFinish()
            return@LaunchedEffect
        }
        Log.d("HomeTour", "step ${index + 1}/${steps.size} '$id' at $target")
        val local = target.translate(-overlayOrigin).inflate(pad)
        if (!ready) {
            spot.snapTo(local)
            ready = true
        } else {
            spot.animateTo(local, tween(420, easing = FastOutSlowInEasing))
        }
    }

    val breathe by rememberInfiniteTransition(label = "tour-ring").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "ring"
    )
    val ring = SmartSpendTheme.colors.accent
    val scrim = Color.Black.copy(alpha = 0.74f)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.positionInRoot()
                overlaySize = it.size
            }
            // Swallow every touch: the live screen stays visible but inert during the tour.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                }
            }
            .semantics { paneTitle = "Guided tour" }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = fadeIn.value
                }
        ) {
            drawRect(scrim)
            if (ready) {
                val r = spot.value
                val corner = CornerRadius(16.dp.toPx())
                drawRoundRect(Color.Transparent, r.topLeft, r.size, corner, blendMode = BlendMode.Clear)
                val grow = 3.dp.toPx() * breathe
                drawRoundRect(
                    ring.copy(alpha = 0.9f - 0.4f * breathe),
                    Offset(r.left - grow, r.top - grow),
                    Size(r.width + grow * 2, r.height + grow * 2),
                    CornerRadius(corner.x + grow),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        if (ready) {
            TooltipCard(
                step = step,
                number = index + 1,
                total = steps.size,
                spot = spot.value,
                overlay = overlaySize,
                cardSize = cardSize,
                onSize = { cardSize = it },
                onSkip = onFinish,
                onNext = { if (index < steps.lastIndex) index++ else onFinish() },
                alpha = fadeIn.value
            )
        }
    }
}

@Composable
private fun TooltipCard(
    step: TourStep,
    number: Int,
    total: Int,
    spot: Rect,
    overlay: IntSize,
    cardSize: IntSize,
    onSize: (IntSize) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    alpha: Float
) {
    val density = LocalDensity.current
    val gap = with(density) { 14.dp.toPx() }
    val side = with(density) { 16.dp.toPx() }
    val arrow = with(density) { 9.dp.toPx() }

    // Below the spotlight when it fits, otherwise above it.
    val below = spot.bottom + gap + arrow + cardSize.height <= overlay.height - side * 2
    val cardTop = if (below) spot.bottom + gap + arrow else spot.top - gap - arrow - cardSize.height
    val maxCardWidth = with(density) { 340.dp.toPx() }
    val cardWidth = minOf(overlay.width - side * 2, maxCardWidth)
    val cardLeft = (spot.center.x - cardWidth / 2f).coerceIn(side, overlay.width - side - cardWidth)
    val arrowX = spot.center.x.coerceIn(cardLeft + arrow * 2.5f, cardLeft + cardWidth - arrow * 2.5f)
    val surface = MaterialTheme.colorScheme.surface

    // Pointer from the card to the spotlight.
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val tipY = if (below) cardTop - arrow else cardTop + cardSize.height + arrow
        val baseY = if (below) cardTop + 1f else cardTop + cardSize.height - 1f
        val path = Path().apply {
            moveTo(arrowX - arrow, baseY)
            lineTo(arrowX, tipY)
            lineTo(arrowX + arrow, baseY)
            close()
        }
        drawPath(path, surface)
    }

    Surface(
        modifier = Modifier
            .offset { IntOffset(cardLeft.roundToInt(), cardTop.roundToInt()) }
            .width(with(density) { cardWidth.toDp() })
            .onSizeChanged(onSize)
            .graphicsLayer { this.alpha = if (cardSize == IntSize.Zero) 0f else alpha },
        shape = MaterialTheme.shapes.medium,
        color = surface,
        shadowElevation = 12.dp
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "tour-step"
        ) { s ->
            Column(
                Modifier
                    .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Step $number of $total", modifier = Modifier.weight(1f))
                    StepDots(number, total)
                }
                Text(s.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(s.body, style = MaterialTheme.typography.bodyMedium, color = SmartSpendTheme.colors.inkMuted)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SKIP TOUR",
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .clickableNoRipple(onSkip),
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartSpendTheme.colors.inkMuted
                    )
                    Surface(
                        onClick = onNext,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            if (number == total) "Done" else "Next",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(number: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(width = if (i == number - 1) 12.dp else 5.dp, height = 5.dp)
                    .background(
                        if (i < number) MaterialTheme.colorScheme.onSurface else SmartSpendTheme.colors.hairline,
                        CircleShape
                    )
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = Role.Button,
        onClick = onClick
    )
}
