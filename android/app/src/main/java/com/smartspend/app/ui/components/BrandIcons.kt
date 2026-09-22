package com.smartspend.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import java.util.Locale

/**
 * Bank and merchant logos, bundled as vector drawables in res/drawable. Nothing is fetched at
 * runtime: a logo API would receive every merchant name the user pays, which contradicts the
 * app's "SMS never leaves the phone" promise. Both lookups return null for anything outside
 * the bundled set, and callers fall back (generic bank glyph / merchant initial).
 *
 * Logos are full-colour brand marks — draw them with Image, never tinted through Icon.
 */

// ── Banks ────────────────────────────────────────────────────────────────────
// Keys are the institution names BankSenderWhitelist.identifyBank() produces (and the backend's
// identify_bank() mirrors), lowercased. Only banks with a bundled drawable appear in the map.
private val bankLogos: Map<String, Int> = mapOf(
    // "hdfc" to R.drawable.bank_hdfc, — added as official assets are sourced
)

/** Spellings the SMS parser and backend emit for the same institution. */
private val bankAliases: Map<String, String> = mapOf(
    "hdfc" to "hdfc", "hdfc bank" to "hdfc",
    "sbi" to "sbi", "state bank of india" to "sbi",
    "icici" to "icici", "icici bank" to "icici",
    "axis" to "axis", "axis bank" to "axis",
    "kotak" to "kotak", "kotak mahindra bank" to "kotak",
    "yes bank" to "yes", "yes" to "yes",
    "pnb" to "pnb", "punjab national bank" to "pnb",
    "paytm" to "paytm", "paytm payments bank" to "paytm",
    "idfc" to "idfc", "idfc first bank" to "idfc",
    "union bank" to "union", "union bank of india" to "union",
    "bank of baroda" to "bob", "bob" to "bob",
    "canara bank" to "canara", "canara" to "canara",
    "rbl bank" to "rbl", "rbl" to "rbl",
    "citi bank" to "citi", "citi" to "citi", "citibank" to "citi",
    "federal bank" to "federal", "federal" to "federal",
    "amex" to "amex", "american express" to "amex"
)

@DrawableRes
private fun bankLogoRes(bankId: String?): Int? {
    val key = bankId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return bankAliases[key]?.let(bankLogos::get)
}

/** Bundled logo for a bank, or null (caller shows the generic bank glyph). */
@Composable
fun BankIcon(bankId: String?): ImageVector? = bankLogoRes(bankId)?.let { ImageVector.vectorResource(it) }

// ── Merchants ────────────────────────────────────────────────────────────────
// Canonical names from backend/categorizer/data/merchants.json. Only merchants with a bundled
// drawable appear here; the curated set is the real top merchants by frequency.
private val merchantLogos: Map<String, Int> = mapOf(
    // "amazon" to R.drawable.merchant_amazon, — added as official assets are sourced
)

/**
 * Raw SMS merchant strings ("AMAZON PAY INDIA", "BUNDL TECHNOLOGIES") → canonical key. Built
 * from the merchants.json aliases of the curated set only.
 */
private val merchantAliases: Map<String, String> = mapOf()

@DrawableRes
private fun merchantLogoRes(merchantName: String?): Int? {
    val raw = merchantName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    merchantLogos[raw]?.let { return it }
    // Longest alias first, so "amazon pay" wins over "amazon" when both exist.
    val hit = merchantAliases.entries
        .sortedByDescending { it.key.length }
        .firstOrNull { raw.contains(it.key) } ?: return null
    return merchantLogos[hit.value]
}

/** Bundled logo for a merchant, or null (caller keeps the initial-letter avatar). */
@Composable
fun MerchantIcon(merchantName: String?): ImageVector? =
    merchantLogoRes(merchantName)?.let { ImageVector.vectorResource(it) }
