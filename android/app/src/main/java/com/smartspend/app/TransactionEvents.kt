package com.smartspend.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App-wide "transactions changed" signal.
 *
 * Bumped when a background SMS sync saves a new transaction or the app returns to the
 * foreground; every open screen that reads [version] (via rememberTransactionsVersion)
 * reloads its data, so new payments appear without pull-to-refresh.
 */
object TransactionEvents {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun notifyChanged() {
        _version.update { it + 1 }
    }
}
