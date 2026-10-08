package com.smartspend.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartspend.app.ui.theme.CategoryIcon
import com.smartspend.app.ui.theme.FinzyyTheme
import com.smartspend.app.ui.theme.WideFamily
import java.util.Locale

/**
 * The single way a transaction or category is represented visually.
 *
 * A row about a **merchant** (merchant given): the bundled brand logo if [MerchantIcon] has one,
 * otherwise the merchant's initial in the category colour — the existing fallback, unchanged.
 * A row about a **category** (no merchant): the category's library icon from [CategoryIcon].
 *
 * Merchant rows deliberately don't fall to the category icon: a list of twenty identical
 * shopping bags says less than twenty different initials.
 */
@Composable
fun MerchantAvatar(
    category: String?,
    modifier: Modifier = Modifier,
    merchant: String? = null,
    size: Dp = 44.dp
) {
    val colors = FinzyyTheme.categories[category]
    val hasMerchant = !merchant.isNullOrBlank()
    val logo = if (hasMerchant) MerchantIcon(merchant) else null

    Box(
        modifier = modifier
            .size(size)
            // A squircle rather than a circle: it sits better in a chunky card stack,
            // and keeps square brand marks from being clipped at the corners.
            .clip(RoundedCornerShape(percent = 30))
            // Logos carry their own colours, so they sit on plain white; everything else is an
            // outlined tile in the ledger's ink, with no tint.
            .background(if (logo != null) Color.White else colors.container)
            .border(1.dp, FinzyyTheme.colors.hairline, RoundedCornerShape(percent = 30)),
        contentAlignment = Alignment.Center
    ) {
        when {
            logo != null -> Image(
                imageVector = logo,
                contentDescription = null,
                modifier = Modifier.size(size * 0.7f)
            )

            hasMerchant -> Text(
                text = initialFor(merchant, category),
                color = colors.accent,
                fontFamily = WideFamily,
                fontWeight = FontWeight.Bold,
                // Scaled off the chip, not a fixed sp, so the same component works at
                // 28dp in a dense list and 64dp on a detail header.
                fontSize = (size.value * 0.36f).sp
            )

            else -> Icon(
                imageVector = CategoryIcon(category),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

private fun initialFor(merchant: String?, category: String?): String {
    val source = merchant?.trim()?.takeIf { it.isNotEmpty() }
        ?: category?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "?"
    // Skip leading punctuation from raw SMS merchant strings ("*AMAZON", "@swiggy").
    val firstLetter = source.firstOrNull { it.isLetterOrDigit() } ?: return "?"
    return firstLetter.uppercase(Locale.ROOT)
}
