package com.smartspend.app.ui.categories

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.TransactionData
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.ChoiceChipRow
import com.smartspend.app.ui.components.EmptyNote
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.LegendSwatch
import com.smartspend.app.ui.components.MerchantAvatar
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SegmentedControl
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.TransactionRow
import com.smartspend.app.ui.components.TransactionSheet
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.Hairline
import com.smartspend.app.ui.components.ShareStrip
import com.smartspend.app.ui.components.foldToShares
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.toView
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.LedgerAmount
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

enum class BreakdownPeriod(val label: String, val months: Int, val offset: Int) {
    ThisMonth("This month", 1, 0),
    LastMonth("Last month", 1, 1),
    ThreeMonths("Last 3 months", 3, 0),
    SixMonths("Last 6 months", 6, 0);

    /** Calendar months covered, oldest first. Category totals come per month from the backend. */
    fun monthList(): List<YearMonth> {
        val newest = YearMonth.now().minusMonths(offset.toLong())
        return (months - 1 downTo 0).map { newest.minusMonths(it.toLong()) }
    }

    val start: LocalDate get() = monthList().first().atDay(1)
    val end: LocalDate get() = minOf(monthList().last().atEndOfMonth(), LocalDate.now())
}

data class CategoryTotal(val category: String, val total: Double, val count: Int)

private data class MerchantTotal(val name: String, val category: String, val total: Double, val txs: List<TransactionData>)

@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onBudget: () -> Unit,
    onCategory: (String) -> Unit
) {
    var period by rememberSaveable { mutableStateOf(BreakdownPeriod.ThisMonth) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var categories by remember { mutableStateOf<List<CategoryTotal>?>(null) }
    var catError by remember { mutableStateOf<String?>(null) }
    var merchants by remember { mutableStateOf<List<MerchantTotal>?>(null) }
    var merchError by remember { mutableStateOf<String?>(null) }
    var openTx by remember { mutableStateOf<TxView?>(null) }

    // Category totals come from the backend's monthly aggregate — the same numbers Home shows.
    LaunchedEffect(period, reloadKey) {
        categories = null
        catError = null
        val summaries = SpendData.monthSummaries(period.monthList())
        if (summaries.all { it.second == null }) {
            catError = "Category totals didn't load."
            return@LaunchedEffect
        }
        categories = summaries.flatMap { it.second?.categories.orEmpty() }
            .groupBy { it.category }
            .map { (name, items) -> CategoryTotal(name, items.sumOf { it.total }, items.sumOf { it.transaction_count }) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
    }

    // No endpoint groups by merchant for a date range, so merchants are grouped here.
    LaunchedEffect(period, tab, reloadKey) {
        if (tab != 1) return@LaunchedEffect
        merchants = null
        merchError = null
        merchants = try {
            SpendData.transactions(period.start, period.end, type = "debit", includeTransfers = false)
                .groupBy { it.merchant?.trim()?.lowercase(Locale.ROOT).orEmpty().ifEmpty { "—" } }
                .map { (_, list) ->
                    val name = list.first().merchant?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unnamed payee"
                    val topCategory = list.groupBy { it.category }.maxByOrNull { e -> e.value.sumOf { it.amount } }?.key ?: "Other"
                    MerchantTotal(name, topCategory, list.sumOf { it.amount }, list)
                }
                .sortedByDescending { it.total }
        } catch (e: Exception) {
            merchError = e.localizedMessage ?: "Merchants didn't load."
            null
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScreenHeader(title = "Spending breakdown", onBack = onBack) }
            item {
                ChoiceChipRow(
                    options = BreakdownPeriod.entries,
                    selected = period,
                    label = { it.label },
                    onSelect = { period = it }
                )
            }
            item {
                SegmentedControl(
                    options = listOf("Categories", "Merchants"),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = ScreenGutter)
                )
            }

            if (tab == 0) {
                categoriesTab(
                    categories = categories,
                    error = catError,
                    period = period,
                    onRetry = { reloadKey++ },
                    onBudget = onBudget,
                    onCategory = onCategory
                )
            } else {
                merchantsTab(
                    merchants = merchants,
                    error = merchError,
                    onRetry = { reloadKey++ },
                    onOpenTx = { openTx = it }
                )
            }
        }
    }

    openTx?.let { tx ->
        TransactionSheet(tx = tx, onDismiss = { openTx = null }, onChanged = { reloadKey++ })
    }
}

private fun LazyListScope.categoriesTab(
    categories: List<CategoryTotal>?,
    error: String?,
    period: BreakdownPeriod,
    onRetry: () -> Unit,
    onBudget: () -> Unit,
    onCategory: (String) -> Unit
) {
    when {
        error != null -> item { ErrorPanel(error, onRetry) }
        categories == null -> item { SkeletonBlocks(listOf(320.dp, 60.dp, 240.dp)) }
        categories.isEmpty() -> item {
            EmptyNote(
                "No spending ${period.label.lowercase(Locale.ENGLISH)}",
                "Once transactions arrive they're sorted into categories here.",
                Modifier.padding(top = 24.dp)
            )
        }
        else -> {
            item { DonutBlock(categories, period) }
            item {
                PrimaryButton(
                    "Set monthly budget",
                    onClick = onBudget,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenGutter)
                )
            }
            item {
                Column(Modifier.padding(horizontal = ScreenGutter - 16.dp)) {
                    categories.forEachIndexed { i, c ->
                        if (i > 0) Hairline(Modifier.padding(start = 70.dp, end = 16.dp))
                        CategoryListRow(c, onClick = { onCategory(c.category) })
                    }
                }
            }
        }
    }
}

/**
 * The period's total as the headline figure, then its split as one engraved strip of ink
 * shades — the category rows below are the readable twin, so no shade is read alone.
 */
@Composable
private fun DonutBlock(categories: List<CategoryTotal>, period: BreakdownPeriod) {
    val colors = SmartSpendTheme.colors
    val slices = remember(categories, colors) {
        foldToShares(categories, { it.total }, { it.category }, colors.shareShades)
    }
    val total = categories.sumOf { it.total }

    Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("Spent · ${period.label}")
        Text(
            money(total),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
        Spacer(Modifier.height(12.dp))
        ShareStrip(slices)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            slices.take(3).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendSwatch(s.color)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${s.label} ${(s.value / total * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryListRow(c: CategoryTotal, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantAvatar(category = c.category, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.category, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${c.count} ${if (c.count == 1) "transaction" else "transactions"}",
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkMuted
            )
        }
        Text(money(c.total), style = LedgerAmount, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
    }
}

private fun LazyListScope.merchantsTab(
    merchants: List<MerchantTotal>?,
    error: String?,
    onRetry: () -> Unit,
    onOpenTx: (TxView) -> Unit
) {
    when {
        error != null -> item { ErrorPanel(error, onRetry) }
        merchants == null -> item { SkeletonBlocks(listOf(420.dp)) }
        merchants.isEmpty() -> item { EmptyNote("No merchants yet", "Payees show up here as you spend.", Modifier.padding(top = 24.dp)) }
        else -> {
            item {
                Eyebrow(
                    "${merchants.size} merchants · ${money(merchants.sumOf { it.total })}",
                    modifier = Modifier.padding(start = ScreenGutter + 4.dp, top = 4.dp)
                )
            }
            item {
                Column(Modifier.padding(horizontal = ScreenGutter - 16.dp)) {
                    merchants.forEachIndexed { i, m ->
                        if (i > 0) Hairline(Modifier.padding(start = 70.dp, end = 16.dp))
                        MerchantRow(m, onOpenTx)
                    }
                }
            }
        }
    }
}

/** Tapping a merchant unfolds its payments in place, so comparing merchants doesn't mean paging back and forth. */
@Composable
private fun MerchantRow(m: MerchantTotal, onOpenTx: (TxView) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MerchantAvatar(category = m.category, merchant = m.name, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${m.txs.size} ${if (m.txs.size == 1) "payment" else "payments"} · ${m.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartSpendTheme.colors.inkMuted
                )
            }
            Text(money(m.total), style = LedgerAmount, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide payments" else "Show payments",
                tint = SmartSpendTheme.colors.inkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 40.dp)) {
                m.txs.sortedByDescending { it.date }.forEach { tx ->
                    val view = tx.toView()
                    TransactionRow(tx = view, onClick = { onOpenTx(view) })
                }
            }
        }
    }
}
