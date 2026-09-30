package com.smartspend.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.smartspend.app.ui.components.Guilloche
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private const val PAGE_COUNT = 3
private val HeroHeight = 300.dp

private data class PageCopy(val headline: String, val subcopy: String)

private val pages = listOf(
    PageCopy(
        "Detected the moment\nyou pay",
        "SmartSpend reads your bank SMS and turns it into a categorized transaction — no typing, nothing to remember."
    ),
    PageCopy(
        "Sorted into the\nright category",
        "A 5-layer engine matches every transaction to a category — and remembers your corrections for next time."
    ),
    PageCopy(
        "Stay ahead of\nyour budget",
        "Set limits per category and get warned before you go over — not after."
    )
)

/**
 * Three-page carousel shown after the splash whenever nobody is signed in. The last page ends
 * in Sign up / Log in; Skip jumps straight to Log in. Fully static — no network calls.
 */
@Composable
fun OnboardingCarousel(
    onSignUp: () -> Unit,
    onLogIn: () -> Unit,
    startPage: Int = 0
) {
    val pager = rememberPagerState(initialPage = startPage) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGE_COUNT - 1

    fun previous() = scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }

    // System back steps through the pages before it leaves the app.
    BackHandler(enabled = pager.currentPage > 0) { previous() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                OnboardingPage(index = index, isCurrent = pager.currentPage == index)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp)
            ) {
                // Each dot's width follows the pager's live position, so a half swipe shows a
                // half-grown dot — the indicator tracks the finger, not just button taps.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val position = pager.currentPage + pager.currentPageOffsetFraction
                    repeat(PAGE_COUNT) { i ->
                        val active = (1f - (position - i).absoluteValue).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(lerp(6f, 20f, active).dp)
                                .background(
                                    lerpColor(SmartSpendTheme.colors.hairline, MaterialTheme.colorScheme.primary, active),
                                    RoundedCornerShape(3.dp)
                                )
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { if (last) onSignUp() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (last) "Sign up" else "Next", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                // The second button grows in on the last page rather than popping, so the page
                // above shrinks smoothly instead of jumping.
                AnimatedVisibility(
                    visible = last,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedButton(
                        onClick = onLogIn,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
                    ) {
                        Text("Log in", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        }

        // Back to the previous page — pages 2 and 3 only.
        AnimatedVisibility(
            visible = pager.currentPage > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp, start = 12.dp)
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Previous page") { previous() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Text(
            "Skip",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .clickable(role = Role.Button) { onLogIn() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f))
        )
    }
}

@Composable
private fun OnboardingPage(index: Int, isCurrent: Boolean) {
    // Restart the scene each time the page becomes current, so its entrance plays on arrival.
    var runs by remember { mutableIntStateOf(0) }
    LaunchedEffect(isCurrent) { if (isCurrent) runs++ }
    val clock = rememberClock(runs)
    val t = if (runs == 0) 0L else clock.value
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Scrolls: the hero is a fixed 300dp scene, so on a short screen or at a large font size the
    // headline and body would otherwise run under the pinned buttons with no way to read them.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Hero sits above the content so the page-1 result card can break out over it.
        Box(
            Modifier
                .zIndex(1f)
                .fillMaxWidth()
                .height(HeroHeight + statusTop)
                .cssLinearGradient(160f, *heroGradient(index))
        ) {
            Guilloche(color = OnboardingColors.Gold, fadeTo = Color.Transparent, modifier = Modifier.matchParentSize(), centerX = 0.78f, centerY = 0.42f, strength = 0.9f)
            GhostRupee(size = 210, alpha = 0.06f, modifier = Modifier.offset(x = (-30).dp, y = (-34).dp))
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = statusTop)
            ) {
                when (index) {
                    0 -> DetectScene(t)
                    1 -> SortScene(t)
                    else -> BudgetScene(t)
                }
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 60.dp)) {
            val copy = pages[index]
            Text(
                copy.headline,
                style = TextStyle(
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 27.6.sp,
                    letterSpacing = (-0.02).em,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(Modifier.height(10.dp))
            Text(
                copy.subcopy,
                modifier = Modifier.widthIn(max = 300.dp),
                style = TextStyle(fontSize = 14.5.sp, lineHeight = 22.5.sp, color = SmartSpendTheme.colors.inkMuted)
            )
        }
    }
}

private fun heroGradient(index: Int): Array<Pair<Float, Color>> = when (index) {
    0 -> arrayOf(0f to OnboardingColors.Brand, 1f to OnboardingColors.BrandDeep)
    1 -> arrayOf(0f to OnboardingColors.Green, 1f to OnboardingColors.GreenDeep)
    else -> arrayOf(0f to OnboardingColors.Amber, 1f to OnboardingColors.AmberDeep)
}

// ── Page 1: an SMS becomes a transaction ─────────────────────────────────────

@Composable
private fun BoxScope.DetectScene(t: Long) {
    // SMS card: cardIn 0.5s from 0.15s, then smsFloat 3.4s loop from 0.65s.
    val cardIn = once(t, 150, 500, CardEase)
    val float = loop(t, 650, 3400)?.let { pingPong(it) } ?: 0f
    Column(
        Modifier
            .offset(x = 24.dp, y = 54.dp)
            .zIndex(2f)
            .graphicsLayer {
                alpha = cardIn
                rotationZ = lerp(-5f, -3.2f, float)
                translationY = (-8).dp.toPx() * (1f - cardIn) - 6.dp.toPx() * float
            }
            .width(192.dp)
            .shadow(16.dp, RoundedCornerShape(14.dp))
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(OnboardingColors.Brand, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("H", style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White))
            }
            Spacer(Modifier.width(8.dp))
            Text("HDFC Bank", style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OnboardingColors.InkSoft))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            buildAnnotatedString {
                append("Rs.")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OnboardingColors.Brand)) { append("420.00") }
                append(" debited via UPI to SWIGGY on 15-Sep")
            },
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, color = OnboardingColors.Ink)
        )
    }

    // Trail dots toward the result card: fade to 0.6 over 0.3s, 80ms apart.
    listOf(Triple(145, 150, 500), Triple(160, 163, 580), Triple(175, 176, 660)).forEach { (x, y, d) ->
        Box(
            Modifier
                .offset(x.dp, y.dp)
                .zIndex(2f)
                .graphicsLayer { alpha = 0.6f * once(t, d, 300) }
                .size(5.dp)
                .background(Color.White, CircleShape)
        )
    }

    // Result card: cardIn2 0.5s from 0.75s, resultFloat 4s from 1.25s.
    val resultIn = once(t, 750, 500, CardEase)
    val resultFloat = loop(t, 1250, 4000)?.let { pingPong(it) } ?: 0f
    Box(
        Modifier
            .padding(start = 90.dp, end = 24.dp)
            .offset(y = 190.dp)
            .zIndex(3f)
            .graphicsLayer {
                alpha = resultIn
                val s = lerp(0.96f, 1f, resultIn)
                scaleX = s
                scaleY = s
                translationY = 10.dp.toPx() * (1f - resultIn) - 5.dp.toPx() * resultFloat
            }
    ) {
        // Offset backing card, peeking out lower-right.
        Box(
            Modifier
                .matchParentSize()
                // CSS inset: 10px -8px -12px 8px → same size, shifted right 8 and down ~11.
                .offset(x = 8.dp, y = 11.dp)
                .background(OnboardingColors.Gold.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(OnboardingColors.FoodTint, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("S", style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = OnboardingColors.FoodInk))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Swiggy", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnboardingColors.Ink))
                Text("Food & Dining", style = TextStyle(fontSize = 11.5.sp, color = OnboardingColors.InkSoft))
            }
            Text("−₹420", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = OnboardingColors.Ink))
        }
        // "Auto-detected" badge: gentle 2.2s pulse from 1.6s.
        val pulse = loop(t, 1600, 2200)?.let { pingPong(it) } ?: 0f
        Text(
            "Auto-detected",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-9).dp)
                .graphicsLayer {
                    val s = lerp(1f, 1.07f, pulse)
                    scaleX = s
                    scaleY = s
                }
                .background(OnboardingColors.Ink, RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        )
    }
}

// ── Page 2: one category wins ────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.SortScene(t: Long) {
    // chipIn 0.4s, staggered 70ms; the winner keeps the last slot's delay, then pops on a 2.6s loop.
    val chips = listOf("Shopping" to 150, "Travel" to 220, "Food & Dining" to 430, "Utilities" to 360, "Other" to 430)
    FlowRow(
        Modifier
            .padding(start = 20.dp, end = 20.dp)
            .offset(y = 44.dp)
            .zIndex(2f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { (label, delay) ->
            val winner = label == "Food & Dining"
            val p = once(t, delay, 400)
            val pop = if (winner) loop(t, 1100, 2600)?.let { pingPong(it) } ?: 0f else 0f
            val shape = RoundedCornerShape(20.dp)
            Text(
                label,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = p
                        translationY = 8.dp.toPx() * (1f - p)
                        val s = lerp(1f, 1.06f, pop)
                        scaleX = s
                        scaleY = s
                    }
                    .then(
                        if (winner) Modifier.shadow(10.dp, shape).background(Color.White, shape)
                        else Modifier
                            .background(Color.White.copy(alpha = 0.16f), shape)
                            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontWeight = if (winner) FontWeight.Bold else FontWeight.Medium,
                    color = if (winner) OnboardingColors.Ink else Color.White.copy(alpha = 0.6f)
                )
            )
        }
    }

    val cardIn = once(t, 750, 500, CardEase)
    val float = loop(t, 1250, 4000)?.let { pingPong(it) } ?: 0f
    Column(
        Modifier
            .padding(start = 24.dp, end = 24.dp)
            .offset(y = 132.dp)
            .zIndex(3f)
            .graphicsLayer {
                alpha = cardIn
                translationY = 10.dp.toPx() * (1f - cardIn) - 5.dp.toPx() * float
            }
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Swiggy",
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = OnboardingColors.Ink)
            )
            Text(
                "High confidence",
                modifier = Modifier
                    .background(OnboardingColors.Ink, RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildAnnotatedString {
                append("Matched from ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = OnboardingColors.Ink)) { append("255+ known merchants") }
                append(" — learns from every correction you make")
            },
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = OnboardingColors.InkSoft)
        )
    }
}

// ── Page 3: the budget ring closes in ────────────────────────────────────────

private const val BUDGET_USED = 0.71f

@Composable
private fun BoxScope.BudgetScene(t: Long) {
    // Ring stroke fills 1.1s from 0.3s to 71% (the mockup's dashoffset 440 → 128).
    val ring = once(t, 300, 1100, RingEase) * BUDGET_USED
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = 30.dp)
            .size(168.dp)
            .zIndex(2f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(168.dp)) {
            val stroke = 14.dp.toPx()
            val inset = 14.dp.toPx() // r = 70 in a 168 box
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (ring > 0f) {
                drawArc(Color.White, -90f, 360f * ring, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        FadeUp(t, delay = 1000, duration = 500) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("71%", style = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.White))
                Spacer(Modifier.height(2.dp))
                Text("of budget used", style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)))
            }
        }
    }

    val cardIn = once(t, 900, 500, CardEase)
    val float = loop(t, 1400, 4000)?.let { pingPong(it) } ?: 0f
    val fill = once(t, 600, 1000) * BUDGET_USED
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = 22.dp)
            .zIndex(3f)
            .graphicsLayer {
                alpha = cardIn
                translationY = 10.dp.toPx() * (1f - cardIn) - 5.dp.toPx() * float
            }
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(16.dp))
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "Food & Dining",
                modifier = Modifier.weight(1f),
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = OnboardingColors.Ink)
            )
            Text("₹4,970 / ₹7,000", style = TextStyle(fontSize = 12.5.sp, color = OnboardingColors.InkSoft))
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(OnboardingColors.Track, RoundedCornerShape(4.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .height(6.dp)
                    .background(OnboardingColors.Gold, RoundedCornerShape(4.dp))
            )
        }
    }
}
