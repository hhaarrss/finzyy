package com.smartspend.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark")
}

/**
 * The user's theme choice from the Account page. Held as Compose state so flipping it
 * restyles every screen immediately, and persisted so it survives a restart.
 */
object ThemePreference {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY = "pref_theme"

    var mode by mutableStateOf(ThemeMode.System)
        private set

    fun load(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    fun set(context: Context, value: ThemeMode) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
