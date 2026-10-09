package com.smartspend.app.ui.addtransaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.theme.CategoryIcon
import com.smartspend.app.ui.theme.FinzyyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What was just saved — enough to say it back to the user. */
data class SavedTx(val amount: Double, val isCredit: Boolean, val category: String, val merchant: String)

private const val HOLD_MS = 1500L

/**
 * The moment after Save: a card springs up over a dimmed screen while a burst of gold-foil and
 * ink confetti flies out, the category icon lands in an outlined disc with a check that
 * draws itself, and the amount counts up to its value. Dismisses itself (or on tap) and then
 * calls [onDone] exactly once.
 */
@Composable
fun SaveSuccessPopup(saved: SavedTx, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val haptics = LocalHapticFeedback.current
    val accent = if (saved.isCredit) FinzyyTheme.colors.positive else MaterialTheme.colorScheme.onSurface
    // Ledger look: the burst is gold foil and ink, not a rainbow.
    val confetti = listOf(FinzyyTheme.colors.accent, MaterialTheme.colorScheme.onSurface, FinzyyTheme.colors.inkMuted, FinzyyTheme.colors.accent)

    val scrim = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    val burst = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val count = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val finished = remember { booleanArrayOf(false) }

    suspend fun leave() {
        if (finished[0]) return
        finished[0] = true
        exit.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        done()
    }

    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        launch { scrim.animateTo(1f, tween(180)) }
        launch { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        launch { burst.animateTo(1f, tween(900, easing = LinearEasing)) }
        launch { delay(180); check.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        launch { delay(120); count.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(HOLD_MS)
        leave()
    }

    // Particles are fixed per popup: angle, distance and size vary so the burst looks thrown, not stamped.
    val particles = remember {
        List(14) { i ->
            val angle = (i / 14f) * 2 * PI.toFloat() + (i % 3) * 0.21f
            Particle(angle, distance = 92f + (i * 37 % 5) * 14f, size = 6f + (i * 13 % 4) * 2f, square = i % 2 == 0, spin = (i % 5 - 2) * 140f)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Color.Black.copy(alpha = 0.42f * scrim.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "${if (saved.isCredit) "Income" else "Expense"} of ${money(saved.amount)} saved to ${saved.category}"
            },
        contentAlignment = Alignment.Center
    ) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Box(contentAlignment = Alignment.Center) {
            // Confetti burst behind the card.
            Canvas(Modifier.size(320.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f - 40.dp.toPx())
                val t = burst.value
                val fade = (1f - t).coerceIn(0f, 1f)
                particles.forEachIndexed { i, p ->
                    val d = p.distance.dp.toPx() * FastOutSlowInEasing.transform(t)
                    val gravity = 60.dp.toPx() * t * t
                    val pos = Offset(c.x + cos(p.angle) * d, c.y + sin(p.angle) * d + gravity)
                    val s = p.size.dp.toPx()
                    val color = confetti[i % confetti.size].copy(alpha = fade)
                    rotate(p.spin * t, pos) {
                        if (p.square) {
                            drawRoundRect(color, Offset(pos.x - s / 2, pos.y - s / 4), Size(s, s / 2), CornerRadius(s / 6))
                        } else {
                            drawCircle(color, s / 2.4f, pos)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .graphicsLayer {
                        val sc = pop.value * (1f - 0.08f * exit.value)
                        scaleX = sc
                        scaleY = sc
                        translationY = 24.dp.toPx() * exit.value
                    }
                    .widthIn(max = 300.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { leave() }
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp
            ) {
                Column(
                    Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(76.dp)
                                .border(1.dp, FinzyyTheme.colors.hairline, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(CategoryIcon(saved.category), contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
                        }
                        // Check badge that draws itself onto the disc's corner.
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .graphicsLayer {
                                    val sc = check.value.coerceAtLeast(0.001f)
                                    scaleX = 0.6f + 0.4f * sc
                                    scaleY = 0.6f + 0.4f * sc
                                    alpha = check.value
                                }
                                .size(28.dp)
                                .background(FinzyyTheme.colors.positive, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.size(14.dp)) {
                                val path = Path().apply {
                                    moveTo(size.width * 0.12f, size.height * 0.52f)
                                    lineTo(size.width * 0.42f, size.height * 0.80f)
                                    lineTo(size.width * 0.90f, size.height * 0.22f)
                                }
                                val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                                val partial = Path()
                                measure.getSegment(0f, measure.length * check.value, partial, true)
                                drawPath(partial, Color.White, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        (if (saved.isCredit) "+" else "") + money(saved.amount * count.value),
                        style = MaterialTheme.typography.displaySmall,
                        color = if (saved.isCredit) FinzyyTheme.colors.positive else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (saved.isCredit) "Income added" else "Expense added",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${saved.category} · ${saved.merchant}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FinzyyTheme.colors.inkMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private class Particle(val angle: Float, val distance: Float, val size: Float, val square: Boolean, val spin: Float)
