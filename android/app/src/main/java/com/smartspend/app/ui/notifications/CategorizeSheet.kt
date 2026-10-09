package com.smartspend.app.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.RecategorizePayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.TransactionEvents
import com.smartspend.app.ui.components.CategoryCache
import com.smartspend.app.ui.components.CategoryChip
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.pickable
import com.smartspend.app.ui.theme.Categories
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch

/**
 * The one place a category is saved from outside the transaction detail sheet — used by both
 * the notification's Categorize action and the Notifications page, so they cannot drift.
 *
 * It is the same call the in-app sheet makes: PATCH /recategorize, which files the
 * transaction and writes the user's merchant mapping (categoriser Layer 1). The mapping is
 * keyed on [merchantRaw] (what the bank sent) when known.
 */
suspend fun fileTransaction(txId: Int, merchantRaw: String?, merchant: String?, category: String): Boolean {
    val ok = runCatching {
        RetrofitClient.apiService.recategorizeTransaction(
            "", txId,
            RecategorizePayload(
                transaction_id = txId,
                merchant_raw = merchantRaw ?: merchant ?: category,
                new_category = category,
                display_name = merchant
            )
        ).isSuccessful
    }.getOrDefault(false)
    if (ok) TransactionEvents.notifyChanged()
    return ok
}

/** Category picker over whatever is on screen: chips only, saves on tap, stays open on failure. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorizeSheet(
    amount: Double,
    credit: Boolean,
    merchant: String,
    current: String?,
    onPick: suspend (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var lists by remember { mutableStateOf(CategoryCache.lists) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (lists == null) lists = runCatching { CategoryCache.get() }.getOrNull()
    }
    // Backend list when we have it (same as the app); the built-in list if it can't be reached.
    val options = (lists?.let { if (credit) it.credit else it.debit } ?: if (credit) Categories.credit else Categories.debit).pickable()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    (if (credit) "+" else "") + money(amount) + " · " + merchant,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Pick a category. We'll file $merchant the same way next time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartSpendTheme.colors.inkMuted
                )
            }
            Eyebrow(if (busy != null) "Saving…" else "Category")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { name ->
                    CategoryChip(
                        category = name,
                        selected = name == busy || (busy == null && name.equals(current, ignoreCase = true)),
                        onClick = {
                            if (busy != null) return@CategoryChip
                            busy = name
                            error = null
                            scope.launch {
                                if (!onPick(name)) {
                                    busy = null
                                    error = "Couldn't save — check your connection and try again."
                                }
                            }
                        }
                    )
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
