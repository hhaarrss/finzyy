package com.smartspend.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Ledger type: wide figures and titles, a plain sans for anything read as a sentence, and a
 * mono face for statement-style detail. Hierarchy comes from width and scale, not from
 * colour or boxes — the steep step from display to body is what makes the amount dominate.
 */
val FinzyyTypography = Typography(
    // Hero amount — the one number the screen exists for.
    displayLarge = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1.3).sp),
    displayMedium = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.0).sp),
    displaySmall = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    // Screen titles.
    headlineMedium = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
    // Section titles ("Recent", "Where it went").
    titleLarge = TextStyle(fontFamily = WideFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    // Row titles — merchant names, setting names.
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    // Eyebrows ("SPENT · SEPTEMBER") — mono, uppercase at call sites, wide tracking.
    labelMedium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
    // Statement detail ("FOOD · TODAY 13:10 · HDFC").
    labelSmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
)

/** Amounts must align on the decimal in any vertical list, which proportional digits break. */
val TabularAmount = TextStyle(
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.End
)

/** A single row's amount: wide, tabular, right-aligned. */
val LedgerAmount = TextStyle(
    fontFamily = WideFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.5.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.End
)
