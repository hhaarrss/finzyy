package com.smartspend.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.smartspend.app.TransactionEvents

/**
 * Changes whenever new transactions arrive in the background. Add it to a screen's
 * LaunchedEffect keys so the screen reloads by itself.
 */
@Composable
fun rememberTransactionsVersion(): Int {
    val version by TransactionEvents.version.collectAsState()
    return version
}
