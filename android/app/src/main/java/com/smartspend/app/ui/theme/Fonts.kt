package com.smartspend.app.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * The two faces of the ledger look, as single switch points:
 *
 *  - [WideFamily]: Archivo at 125% width — amounts, headlines, section titles.
 *  - [MonoFamily]: JetBrains Mono — ledger detail (dates, banks, card digits, eyebrows).
 *
 * Until the font files are bundled in res/font these fall back to the system sans and the
 * system monospace, so everything already renders with the right roles; bundling them is a
 * change to these two lines only.
 */
val WideFamily: FontFamily = FontFamily.Default
val MonoFamily: FontFamily = FontFamily.Monospace
