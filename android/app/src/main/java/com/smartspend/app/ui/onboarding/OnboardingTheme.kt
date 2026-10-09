package com.smartspend.app.ui.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Tokens for the splash + onboarding sequence, from the approved HTML mockup
 * (smartspend_splash_onboarding_concept.html), re-toned to the ledger look: black ground,
 * white cards, one engraved gold.
 */
internal object OnboardingColors {
    // Splash: 3-stop 160° sweep from graphite to pure black.
    val SplashTop = Color(0xFF1C1C1C)
    val SplashMid = Color(0xFF0B0B0B)
    val SplashDeep = Color(0xFF000000)

    // Every carousel hero shares the same black note; the engraving differentiates, not hue.
    val Brand = Color(0xFF0A0A0A)           // ink — mark glyph, bank badge, emphasis in cards
    val BrandDeep = Color(0xFF000000)
    val Green = Color(0xFF161616)           // page 2 hero top
    val GreenDeep = Color(0xFF000000)
    val Amber = Color(0xFF141414)           // page 3 hero top
    val AmberDeep = Color(0xFF000000)

    val Gold = Color(0xFFB8965A)            // engraving + the budget fill
    val MoneyIn = Color(0xFF2F7A4F)         // amounts on the white cards

    val Ink = Color(0xFF0A0A0A)
    val InkSoft = Color(0xFF6B6B66)
    val DotIdle = Color(0xFFD9D5EC)
    val Track = Color(0xFFE1E1DC)
    val MarkFillTop = Color.White
    val MarkFillBottom = Color(0xFFEDEDEA)
    val FoodTint = Color(0xFFF1F1EE)
    val FoodInk = Color(0xFF0A0A0A)
}

/**
 * Display face for headlines, the wordmark and numbers. The mockup specifies Sora; the app
 * bundles no fonts yet, so this is the system face until res/font/sora_*.ttf is added — then
 * only this line changes.
 */
internal val DisplayFont: FontFamily = FontFamily.Default

// ── Motion ────────────────────────────────────────────────────────────────────
// The mockup is CSS keyframes with delays. Driving every element from one clock and pure
// functions of elapsed time reproduces those timings exactly (including staggered infinite
// loops), which chained Compose animations can't do without drift.

internal val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
internal val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
internal val CardEase: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
internal val PulseEase: Easing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
internal val RingEase: Easing = CubicBezierEasing(0.3f, 0.8f, 0.3f, 1f)
/** Milliseconds since this composable entered composition (or since [key] last changed). */
@Composable
internal fun rememberClock(key: Any? = Unit): State<Long> {
    val clock = remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(key) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    return clock
}

/** Progress 0→1 of a one-shot animation (`animation-delay`, `forwards` fill). */
internal fun once(t: Long, delay: Int, duration: Int, easing: Easing = EaseOut): Float =
    easing.transform(((t - delay).toFloat() / duration).coerceIn(0f, 1f))

/** Phase 0→1 of an infinite animation, or null before its delay elapses. */
internal fun loop(t: Long, delay: Int, period: Int): Float? =
    if (t < delay) null else ((t - delay) % period).toFloat() / period

/** A 0→1→0 `0%, 100% { a } 50% { b }` keyframe pair, eased on each half like CSS does. */
internal fun pingPong(phase: Float, easing: Easing = EaseInOut): Float =
    if (phase < 0.5f) easing.transform(phase * 2f) else easing.transform((1f - phase) * 2f)

internal fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
