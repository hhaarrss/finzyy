package com.smartspend.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

// Primary is the ink itself: filled buttons and selected states are black on stone in
// light and white on black in dark. The gold sits in secondary for the rare accent.
private val LightColorScheme = lightColorScheme(
    primary = StoneInk,
    onPrimary = Paper,
    primaryContainer = Color(0xFFEAEAE5),
    onPrimaryContainer = StoneInk,
    secondary = GoldDeep,
    onSecondary = Paper,
    tertiary = MoneyInLight,
    onTertiary = Paper,
    background = Stone,
    onBackground = StoneInk,
    surface = Paper,
    onSurface = StoneInk,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = StoneSoft,
    surfaceContainer = Paper,
    surfaceContainerLow = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = PaperRaised,
    outline = StoneLine,
    outlineVariant = StoneLine,
    error = OverBudgetLight,
    onError = Paper,
    scrim = Color.Black
)

private val DarkColorScheme = darkColorScheme(
    primary = NightInk,
    onPrimary = Night,
    primaryContainer = NightRaised,
    onPrimaryContainer = NightInk,
    secondary = Gold,
    onSecondary = Night,
    tertiary = MoneyIn,
    onTertiary = Night,
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightRaised,
    onSurfaceVariant = NightSoft,
    surfaceContainer = NightSurface,
    surfaceContainerLow = NightSurface,
    surfaceContainerHigh = NightRaised,
    surfaceContainerHighest = NightRaised,
    outline = NightLine,
    outlineVariant = NightLine,
    error = OverBudget,
    onError = Night,
    scrim = Color.Black
)

@Composable
fun SmartSpendTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkSmartSpendColors else LightSmartSpendColors
    val categories = remember(darkTheme) { categoryPalette(darkTheme) }

    CompositionLocalProvider(
        LocalSmartSpendColors provides extended,
        LocalCategoryPalette provides categories
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SmartSpendTypography,
            shapes = SmartSpendShapes,
            content = content
        )
    }
}

/**
 * Accessors for the tokens Material3 does not carry. Mirrors the `MaterialTheme.colorScheme`
 * shape so call sites read the same way: `SmartSpendTheme.colors.positive`.
 */
object SmartSpendTheme {
    val colors: SmartSpendColors
        @Composable @ReadOnlyComposable get() = LocalSmartSpendColors.current

    val categories: CategoryPalette
        @Composable @ReadOnlyComposable get() = LocalCategoryPalette.current
}
