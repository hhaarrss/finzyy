package com.smartspend.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors Material3's [androidx.compose.material3.ColorScheme] has no slot for.
 * Read these through [SmartSpendTheme.colors], never as literals.
 */
@Immutable
data class SmartSpendColors(
    /** Money in: credits, income, refunds. */
    val positive: Color,
    val positiveContainer: Color,
    /** Over a budget limit, destructive actions. */
    val negative: Color,
    val negativeContainer: Color,
    /** Approaching a budget limit — warn, don't alarm. */
    val caution: Color,
    val cautionContainer: Color,
    /** The engraved gold: hero engraving and the one active state on a screen. */
    val accent: Color,
    val onAccent: Color,
    /** The hero "note" card. A plain surface — the engraving carries it, not a fill. */
    val heroSurface: Color,
    val onHeroSurface: Color,
    /** Secondary text — the "FOOD · TODAY 13:10" line under a merchant. */
    val inkMuted: Color,
    /** Idle chart marks, placeholders, disabled glyphs. */
    val inkFaint: Color,
    /** Inset fills: progress tracks, text fields, pressed rows. */
    val subtleSurface: Color,
    /** 1dp rules between ledger rows and around grouped blocks. */
    val hairline: Color,
    /**
     * Shades for share-of-total marks (the "where it went" strip), darkest = biggest.
     * Identity is carried by the row label beside each shade, never by the shade alone.
     */
    val shareShades: List<Color>
)

val LightSmartSpendColors = SmartSpendColors(
    positive = MoneyInLight,
    positiveContainer = MoneyInLight.copy(alpha = 0.10f),
    negative = OverBudgetLight,
    negativeContainer = OverBudgetLight.copy(alpha = 0.10f),
    caution = ApproachingLight,
    cautionContainer = ApproachingLight.copy(alpha = 0.12f),
    accent = GoldDeep,
    onAccent = Paper,
    heroSurface = Paper,
    onHeroSurface = StoneInk,
    inkMuted = StoneSoft,
    inkFaint = StoneFaint,
    subtleSurface = PaperRaised,
    hairline = StoneLine,
    shareShades = listOf(Color(0xFF0A0A0A), Color(0xFF4A4A47), Color(0xFF7A7A75), Color(0xFFA6A6A0), Color(0xFFCFCFC9))
)

val DarkSmartSpendColors = SmartSpendColors(
    positive = MoneyIn,
    positiveContainer = MoneyIn.copy(alpha = 0.12f),
    negative = OverBudget,
    negativeContainer = OverBudget.copy(alpha = 0.12f),
    caution = Approaching,
    cautionContainer = Approaching.copy(alpha = 0.12f),
    accent = Gold,
    onAccent = Night,
    heroSurface = NightSurface,
    onHeroSurface = NightInk,
    inkMuted = NightSoft,
    inkFaint = NightFaint,
    subtleSurface = NightRaised,
    hairline = NightLine,
    shareShades = listOf(Color(0xFFF4F4F0), Color(0xFFB9B9B4), Color(0xFF85857F), Color(0xFF5A5A56), Color(0xFF3A3A37))
)

/**
 * Static rather than dynamic: the whole set swaps at once on a theme change, so tracking
 * reads individually would cost recompositions for a value that changes about twice a day.
 */
internal val LocalSmartSpendColors = staticCompositionLocalOf { DarkSmartSpendColors }
