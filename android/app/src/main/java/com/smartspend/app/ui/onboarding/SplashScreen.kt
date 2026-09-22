package com.smartspend.app.ui.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val SPLASH_TOTAL_MS = 2400L
private val MarkOvershoot = CubicBezierEasing(0.16f, 1.2f, 0.3f, 1f)

/**
 * One orchestrated moment: the mark springs in inside expanding pulse rings, then wordmark,
 * tagline and loading dots fade up in sequence. Timings are the mockup's CSS delays verbatim.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        delay(SPLASH_TOTAL_MS)
        finish()
    }
    val clock = rememberClock()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .cssLinearGradient(
                160f,
                0f to OnboardingColors.SplashTop,
                0.55f to OnboardingColors.SplashMid,
                1f to OnboardingColors.SplashDeep
            )
            .semantics { contentDescription = "SmartSpend is starting" },
        contentAlignment = Alignment.Center
    ) {
        // Ghost ₹ marks bleeding off opposite corners.
        GhostRupee(size = 320, alpha = 0.05f, modifier = Modifier.align(Alignment.TopEnd).offset(x = 80.dp, y = (-70).dp))
        GhostRupee(size = 260, alpha = 0.035f, modifier = Modifier.align(Alignment.BottomStart).offset(x = (-90).dp, y = 100.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                val t = clock.value
                // Soft radial glow, fading in first (0.1s delay, 1.2s ease-out).
                Box(
                    Modifier
                        // Overflows the 96dp mark box, as the CSS glow does.
                        .requiredSize(260.dp)
                        .alpha(once(t, 100, 1200))
                        .background(
                            Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.22f),
                                0.7f to Color.White.copy(alpha = 0f)
                            ),
                            CircleShape
                        )
                )
                // Three pulse rings, 2.2s loop, staggered 0.7s.
                listOf(0, 700, 1400).forEach { d -> PulseRing(t, d) }
                // Four orbit dots at the ring's compass points, staggered from 0.9s.
                OrbitDot(t, 900, x = 0.dp, y = (-55).dp)
                OrbitDot(t, 1100, x = 55.dp, y = 0.dp)
                OrbitDot(t, 1300, x = 0.dp, y = 55.dp)
                OrbitDot(t, 1500, x = (-55).dp, y = 0.dp)
                Mark(t)
            }
            Spacer(Modifier.height(24.dp))
            FadeUp(clock.value, delay = 1100, duration = 600) {
                Text(
                    "SmartSpend",
                    style = TextStyle(
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 25.sp,
                        letterSpacing = (-0.02).em,
                        color = Color.White
                    )
                )
            }
            Spacer(Modifier.height(7.dp))
            FadeUp(clock.value, delay = 1350, duration = 600) {
                Text(
                    "Your money, tracked automatically",
                    style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.72f))
                )
            }
        }

        FadeUp(
            clock.value, delay = 1600, duration = 500,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 46.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 150, 300).forEach { d ->
                    val p = loop(clock.value, d, 1200)?.let { pingPong(it) } ?: 0f
                    Box(
                        Modifier
                            .graphicsLayer {
                                translationY = -4.dp.toPx() * p
                                alpha = lerp(0.4f, 1f, p)
                            }
                            .size(5.dp)
                            .background(Color.White.copy(alpha = 0.55f), CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun PulseRing(t: Long, delay: Int) {
    val phase = loop(t, delay, 2200) ?: return
    val p = PulseEase.transform(phase)
    Box(
        Modifier
            .size(96.dp)
            .graphicsLayer {
                val s = lerp(0.4f, 2.4f, p)
                scaleX = s
                scaleY = s
                alpha = lerp(0.9f, 0f, p)
            }
            .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape)
    )
}

@Composable
private fun OrbitDot(t: Long, delay: Int, x: Dp, y: Dp) {
    val phase = loop(t, delay, 2200) ?: return
    val p = pingPong(phase)
    Box(
        Modifier
            .offset(x, y)
            .graphicsLayer {
                alpha = 0.85f * p
                val s = lerp(0.5f, 1f, p)
                scaleX = s
                scaleY = s
            }
            .size(5.dp)
            .background(Color.White, CircleShape)
    )
}

/** 0.75s from 0.3s: scale .4 → 1.08 → 1, rotate −8° → 1° → 0°, the overshoot curve on each leg. */
@Composable
private fun Mark(t: Long) {
    val raw = ((t - 300f) / 750f).coerceIn(0f, 1f)
    val scale: Float
    val rotation: Float
    val alpha: Float
    if (raw < 0.7f) {
        val f = MarkOvershoot.transform(raw / 0.7f)
        scale = lerp(0.4f, 1.08f, f)
        rotation = lerp(-8f, 1f, f)
        alpha = f.coerceIn(0f, 1f)
    } else {
        val f = MarkOvershoot.transform((raw - 0.7f) / 0.3f)
        scale = lerp(1.08f, 1f, f)
        rotation = lerp(1f, 0f, f)
        alpha = 1f
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
                this.alpha = alpha
            }
            .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .size(68.dp)
            .cssLinearGradient(160f, 0f to OnboardingColors.MarkFillTop, 1f to OnboardingColors.MarkFillBottom, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "₹",
            style = TextStyle(
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = OnboardingColors.Brand
            )
        )
    }
}

/** `fadeUp`: opacity 0→1 while rising 6dp. */
@Composable
internal fun FadeUp(
    t: Long,
    delay: Int,
    duration: Int,
    modifier: Modifier = Modifier,
    rise: Dp = 6.dp,
    content: @Composable () -> Unit
) {
    val p = once(t, delay, duration)
    Box(
        modifier.graphicsLayer {
            alpha = p
            translationY = rise.toPx() * (1f - p)
        }
    ) { content() }
}

@Composable
internal fun GhostRupee(size: Int, alpha: Float, modifier: Modifier = Modifier) {
    Text(
        "₹",
        modifier = modifier,
        style = TextStyle(
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = size.sp,
            color = Color.White.copy(alpha = alpha)
        )
    )
}

/**
 * CSS `linear-gradient(<angle>deg, …)`: 0° points up, 90° right, and the gradient line is
 * long enough that the corners get the end colours — Compose's Brush.linearGradient needs the
 * endpoints computed to match.
 */
internal fun Modifier.cssLinearGradient(
    angleDeg: Float,
    vararg stops: Pair<Float, Color>,
    shape: Shape? = null
): Modifier = drawWithCache {
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val brush = Brush.linearGradient(
        *stops,
        start = Offset(c.x - dx * half, c.y - dy * half),
        end = Offset(c.x + dx * half, c.y + dy * half)
    )
    val outline = shape?.createOutline(size, layoutDirection, this)
    onDrawBehind {
        if (outline != null) drawOutline(outline, brush) else drawRect(brush)
    }
}
