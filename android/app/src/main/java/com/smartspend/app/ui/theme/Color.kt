package com.smartspend.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Ledger palette ─────────────────────────────────────────────────────────
// Black-and-white first (dark is the primary theme, CRED-like), with one engraved
// old-gold accent kept for the hero engraving and the single active state on a screen.
// Colour otherwise only means money: green in, red over budget, amber approaching.

// Dark
val Night = Color(0xFF000000)          // ground
val NightSurface = Color(0xFF0E0E0E)   // cards
val NightRaised = Color(0xFF171717)    // tracks, pressed, inset fields
val NightLine = Color(0xFF262626)      // hairlines
val NightInk = Color(0xFFF4F4F0)       // text, primary fill
val NightSoft = Color(0xFF8A8A85)      // secondary text
val NightFaint = Color(0xFF4A4A47)     // idle marks, placeholders

// Light
val Stone = Color(0xFFF1F1EE)          // ground — cool stone, deliberately not cream
val Paper = Color(0xFFFFFFFF)          // cards
val PaperRaised = Color(0xFFF7F7F4)
val StoneLine = Color(0xFFE1E1DC)
val StoneInk = Color(0xFF0A0A0A)
val StoneSoft = Color(0xFF6B6B66)
val StoneFaint = Color(0xFFB3B3AD)

// Engraving
val Gold = Color(0xFFB8965A)
val GoldDeep = Color(0xFF9C7B43)       // same gold, darkened to hold contrast on stone

// Money
val MoneyIn = Color(0xFF8CC7A1)
val MoneyInLight = Color(0xFF2F7A4F)
val OverBudget = Color(0xFFE28A76)
val OverBudgetLight = Color(0xFFB5472F)
val Approaching = Color(0xFFD9B26A)
val ApproachingLight = Color(0xFF9A6B1F)
