package com.smartspend.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.CategoryListsResponse
import com.smartspend.app.HomeRecentTransactionData
import com.smartspend.app.RecategorizePayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.TransactionData
import com.smartspend.app.TransactionUpdatePayload
import com.smartspend.app.ui.theme.FinzyyTheme
import com.smartspend.app.ui.theme.LedgerAmount
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What a row and the detail sheet need, independent of which endpoint the row came from —
 * Home's summary payload carries fewer fields than the full transaction list.
 */
data class TxView(
    val id: Int,
    val amount: Double,
    val isCredit: Boolean,
    val category: String,
    val merchant: String?,
    /** What the bank sent. Corrections are keyed on this, never on the displayed [merchant]. */
    val merchantRaw: String? = null,
    val date: String?,
    val fromSms: Boolean?,
    val needsReview: Boolean,
    val bank: String? = null,
    val last4: String? = null
) {
    val title: String get() = merchant?.takeIf { it.isNotBlank() } ?: category
    val day: LocalDate? get() = parseTxDate(date)
}

fun TransactionData.toView() = TxView(
    id = id, amount = amount, isCredit = isCredit, category = category, merchant = merchant,
    merchantRaw = merchant_raw, date = date, fromSms = isFromSms, needsReview = needsReview, bank = bank, last4 = account_last4
)

fun HomeRecentTransactionData.toView() = TxView(
    id = id, amount = amount, isCredit = type.equals("credit", ignoreCase = true), category = category,
    merchant = merchant, merchantRaw = merchant_raw, date = date, fromSms = null,
    needsReview = review_status?.contains("needs_review", ignoreCase = true) == true
)

@Composable
fun TransactionRow(
    tx: TxView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantAvatar(category = tx.category, merchant = tx.merchant, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tx.needsReview) {
                    Spacer(Modifier.width(6.dp))
                    ReviewFlag()
                }
            }
            // Statement-style detail line: CATEGORY · WHEN · BANK
            Text(
                ledgerMeta(tx, showDate),
                style = MaterialTheme.typography.labelSmall,
                color = FinzyyTheme.colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (tx.isCredit) "+${money(tx.amount)}" else money(tx.amount),
            style = LedgerAmount,
            color = if (tx.isCredit) FinzyyTheme.colors.positive else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The small inverted tag on a row that still needs a category. */
@Composable
fun ReviewFlag() {
    Text(
        "REVIEW",
        modifier = Modifier
            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.surface
    )
}

private fun ledgerMeta(tx: TxView, showDate: Boolean): String {
    val parts = buildList {
        add(if (tx.needsReview) "Unsorted" else tx.category)
        if (showDate) shortDate(tx.date).takeIf { it.isNotEmpty() }?.let(::add)
        tx.bank?.takeIf { it.isNotBlank() && it != "BANK" }?.let { bank ->
            add(listOfNotNull(bank, tx.last4?.let { "••$it" }).joinToString(" "))
        }
    }
    return parts.joinToString(" · ").uppercase()
}

/**
 * Transactions grouped under day headings, as ledger rows split by hairlines. No card per
 * day — the day header and the rules carry the grouping, like a bank statement.
 */
fun LazyListScope.transactionDays(
    txs: List<TxView>,
    onClick: (TxView) -> Unit,
    keyPrefix: String = "tx"
) {
    val days = txs.groupBy { it.day }.toList().sortedByDescending { it.first ?: LocalDate.MIN }
    days.forEach { (day, rows) ->
        item(key = "$keyPrefix-h-$day") {
            val total = rows.filter { !it.isCredit }.sumOf { it.amount }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ScreenGutter, end = ScreenGutter, top = 22.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Eyebrow(day?.let(::dayHeader) ?: "Undated", modifier = Modifier.weight(1f))
                if (total > 0) Eyebrow(money(total), color = MaterialTheme.colorScheme.onBackground)
            }
            Hairline(Modifier.padding(horizontal = ScreenGutter))
        }
        item(key = "$keyPrefix-d-$day") {
            Column(Modifier.padding(horizontal = ScreenGutter - 16.dp)) {
                rows.forEachIndexed { i, tx ->
                    if (i > 0) Hairline(Modifier.padding(start = 68.dp, end = 16.dp))
                    TransactionRow(tx = tx, onClick = { onClick(tx) }, showDate = false)
                }
            }
        }
    }
}

/** Category lists change only with a backend deploy, so one fetch per process is plenty. */
object CategoryCache {
    var lists: CategoryListsResponse? = null
        private set

    suspend fun get(): CategoryListsResponse = lists ?: SpendData.categoryLists().also { lists = it }
}

/**
 * Everything you can do to one transaction: re-file it (which also teaches the categoriser
 * that merchant), correct the payee or amount, or delete it. Replaces the stack of AlertDialogs
 * the old dashboard used for the same three actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionSheet(
    tx: TxView,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var categories by remember { mutableStateOf(CategoryCache.lists) }
    var category by remember { mutableStateOf(tx.category) }
    var editing by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf(tx.merchant.orEmpty()) }
    var amount by remember { mutableStateOf(plainAmount(tx.amount)) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (categories == null) categories = runCatching { CategoryCache.get() }.getOrNull()
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun recategorize(newCategory: String) {
        if (newCategory == category || busy) return
        busy = true
        scope.launch {
            val ok = runCatching {
                RetrofitClient.apiService.recategorizeTransaction(
                    "", tx.id,
                    RecategorizePayload(
                        transaction_id = tx.id,
                        merchant_raw = (tx.merchantRaw ?: tx.merchant)?.takeIf { it.isNotBlank() } ?: tx.category,
                        new_category = newCategory
                    )
                ).isSuccessful
            }.getOrDefault(false)
            busy = false
            if (ok) {
                category = newCategory
                toast("Filed under $newCategory")
                onChanged()
                onDismiss()
            } else toast("Couldn't change the category")
        }
    }

    fun saveEdits() {
        val value = amount.toDoubleOrNull()
        if (value == null || value <= 0) { toast("Enter an amount above zero"); return }
        busy = true
        scope.launch {
            val ok = runCatching {
                RetrofitClient.apiService.patchTransaction(
                    "", tx.id,
                    TransactionUpdatePayload(merchant = merchant.trim().ifEmpty { null }, amount = value)
                ).isSuccessful
            }.getOrDefault(false)
            busy = false
            if (ok) { toast("Saved"); onChanged(); onDismiss() } else toast("Couldn't save changes")
        }
    }

    fun delete() {
        busy = true
        scope.launch {
            val ok = runCatching { RetrofitClient.apiService.deleteTransaction("", tx.id).isSuccessful }
                .getOrDefault(false)
            busy = false
            if (ok) { toast("Transaction deleted"); onChanged(); onDismiss() } else toast("Couldn't delete it")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantAvatar(category = category, merchant = tx.merchant, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tx.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (tx.fromSms == true) {
                            // Bundled bank logo when we have one, otherwise the generic bank glyph.
                            val bankLogo = BankIcon(tx.bank)
                            if (bankLogo != null) {
                                Image(bankLogo, contentDescription = tx.bank, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(
                                    Icons.Rounded.AccountBalance,
                                    contentDescription = null,
                                    tint = FinzyyTheme.colors.inkMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            sourceLine(tx),
                            style = MaterialTheme.typography.bodySmall,
                            color = FinzyyTheme.colors.inkMuted
                        )
                    }
                }
            }

            Text(
                (if (tx.isCredit) "+" else "") + moneyExact(tx.amount),
                style = MaterialTheme.typography.displayMedium,
                color = if (tx.isCredit) FinzyyTheme.colors.positive else MaterialTheme.colorScheme.onSurface
            )

            if (tx.needsReview && category == tx.category) {
                Block(color = FinzyyTheme.colors.cautionContainer, padding = PaddingValues(14.dp)) {
                    Text(
                        "We couldn't tell what this was. Pick a category below — we'll file this merchant the same way next time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("Category")
                val options = categories?.let { if (tx.isCredit) it.credit else it.debit }?.pickable()
                if (options == null) {
                    Text("Loading categories…", style = MaterialTheme.typography.bodyMedium, color = FinzyyTheme.colors.inkMuted)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { name ->
                            CategoryChip(
                                category = name,
                                selected = name.equals(category, ignoreCase = true),
                                onClick = { recategorize(name) }
                            )
                        }
                    }
                }
            }

            if (editing) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text(if (tx.isCredit) "Received from" else "Paid to") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Amount") },
                        prefix = { Text("₹") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Cancel", onClick = { editing = false }, modifier = Modifier.weight(1f))
                        PrimaryButton("Save", onClick = { saveEdits() }, enabled = !busy, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(
                        "Edit details",
                        onClick = { editing = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        "Delete",
                        onClick = { confirmDelete = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("${money(tx.amount)} · ${tx.title}. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; delete() }) {
                    Text("Delete", color = FinzyyTheme.colors.negative)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } }
        )
    }
}

private fun sourceLine(tx: TxView): String {
    val date = shortDate(tx.date)
    val origin = when (tx.fromSms) {
        true -> listOfNotNull("From ${tx.bank ?: "bank"} SMS", tx.last4?.let { "••$it" }).joinToString(" ")
        false -> "Added by you"
        null -> null
    }
    return listOfNotNull(date.ifEmpty { null }, origin).joinToString(" · ")
}

private fun plainAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
