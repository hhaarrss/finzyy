package com.smartspend.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The accent a single category is drawn with: [accent] for the icon glyph, chart slice and
 * emphasis text, [container] for the chip fill behind it.
 */
@Immutable
data class CategoryColors(
    val accent: Color,
    val container: Color
)

/**
 * Category colors are looked up by name because the category set is server-driven — the
 * backend's CANONICAL_CATEGORY_MAP can add a category without an app release, so an
 * unmatched name has to degrade to a usable color rather than crash or render invisible.
 */
@Immutable
class CategoryPalette(
    private val byName: Map<String, CategoryColors>,
    private val fallback: CategoryColors
) {
    operator fun get(category: String?): CategoryColors =
        Categories.canonical(category)?.let { byName[it] } ?: fallback
}

/**
 * Ledger look: categories are not colour-coded. Every category draws in the ink colour on no
 * fill — its icon and name carry identity; colour is reserved for money direction and budget
 * health. Kept as a palette object so a future accent-per-category experiment is one change.
 */
internal fun categoryPalette(dark: Boolean): CategoryPalette {
    val ink = if (dark) NightInk else StoneInk
    val mono = CategoryColors(accent = ink, container = Color.Transparent)
    return CategoryPalette(emptyMap(), mono)
}

internal val LocalCategoryPalette = staticCompositionLocalOf { categoryPalette(dark = true) }
