package com.smartspend.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.smartspend.app.TransactionData
import com.smartspend.app.ui.components.ChoiceChipRow
import com.smartspend.app.ui.components.EmptyNote
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.TransactionSheet
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.isCredit
import com.smartspend.app.ui.components.isFromSms
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.needsReview
import com.smartspend.app.ui.components.toView
import com.smartspend.app.ui.components.transactionDays
import com.smartspend.app.ui.theme.SmartSpendTheme
import java.time.LocalDate

private enum class SearchFilter(val label: String) {
    All("All"),
    Spent("Spent"),
    Received("Received"),
    Sms("From SMS"),
    Manual("Added by me"),
    Review("Needs review")
}

/** How far back search reaches. The list endpoint has no text query, so matching is local. */
private const val SEARCH_WINDOW_MONTHS = 6L

@Composable
fun SearchScreen(onBack: () -> Unit, startWithReview: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(if (startWithReview) SearchFilter.Review else SearchFilter.All) }
    var all by remember { mutableStateOf<List<TransactionData>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var openTx by remember { mutableStateOf<TxView?>(null) }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(reloadKey) {
        error = null
        all = try {
            val end = LocalDate.now()
            SpendData.transactions(end.minusMonths(SEARCH_WINDOW_MONTHS), end)
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Can't reach SmartSpend right now."
            null
        }
    }
    LaunchedEffect(Unit) { if (!startWithReview) focus.requestFocus() }

    val results = remember(all, query, filter) {
        all.orEmpty().filter { tx -> matchesFilter(tx, filter) && matchesQuery(tx, query) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = ScreenGutter, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "Merchant, category, bank or amount",
                                style = MaterialTheme.typography.bodyLarge,
                                color = SmartSpendTheme.colors.inkMuted
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            ChoiceChipRow(
                options = SearchFilter.entries,
                selected = filter,
                label = { it.label },
                onSelect = { filter = it }
            )

            when {
                error != null && all == null -> ErrorPanel(error.orEmpty(), onRetry = { reloadKey++ })
                all == null -> SkeletonBlocks(listOf(72.dp, 220.dp, 150.dp))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    item {
                        val spent = results.filter { !it.isCredit }.sumOf { it.amount }
                        Row(
                            modifier = Modifier.padding(start = ScreenGutter + 4.dp, end = ScreenGutter + 4.dp, top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${results.size} ${if (results.size == 1) "transaction" else "transactions"}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (spent > 0) Eyebrow("${money(spent)} spent")
                        }
                    }
                    if (results.isEmpty()) {
                        item {
                            EmptyNote(
                                title = if (filter == SearchFilter.Review) "Nothing to review" else "No matches",
                                body = if (filter == SearchFilter.Review) "Every transaction has a category."
                                else "Searching the last $SEARCH_WINDOW_MONTHS months. Try a shorter word or another filter.",
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    }
                    transactionDays(results.map { it.toView() }, onClick = { openTx = it })
                }
            }
        }
    }

    openTx?.let { tx ->
        TransactionSheet(tx = tx, onDismiss = { openTx = null }, onChanged = { reloadKey++ })
    }
}

private fun matchesFilter(tx: TransactionData, filter: SearchFilter): Boolean = when (filter) {
    SearchFilter.All -> true
    SearchFilter.Spent -> !tx.isCredit
    SearchFilter.Received -> tx.isCredit
    SearchFilter.Sms -> tx.isFromSms
    SearchFilter.Manual -> !tx.isFromSms
    SearchFilter.Review -> tx.needsReview
}

private fun matchesQuery(tx: TransactionData, raw: String): Boolean {
    val q = raw.trim().lowercase()
    if (q.isEmpty()) return true
    // "499" should find ₹499.00; "4,999" should find 4999.
    val digits = q.filter { it.isDigit() || it == '.' }
    if (digits.isNotEmpty() && digits.length == q.replace(",", "").replace("₹", "").length) {
        val amount = "%.2f".format(tx.amount)
        if (amount.startsWith(digits) || tx.amount.toLong().toString().startsWith(digits)) return true
    }
    return listOfNotNull(tx.merchant, tx.category, tx.bank, tx.subcategory, tx.transfer_to)
        .any { it.lowercase().contains(q) }
}
