package com.smartspend.app.ui.categories

import androidx.compose.foundation.layout.Arrangement
import com.smartspend.app.ui.components.rememberTransactionsVersion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import com.smartspend.app.CategorySummaryItem
import com.smartspend.app.TransactionData
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.ChartBar
import com.smartspend.app.ui.components.EmptyNote
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.MerchantAvatar
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.SpendBarChart
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.ThinProgress
import com.smartspend.app.ui.components.TransactionSheet
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.budgetColor
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.toView
import com.smartspend.app.ui.components.transactionDays
import com.smartspend.app.ui.theme.SmartSpendTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

private const val HISTORY_MONTHS = 6

/**
 * One category over the last six months. The chart is also the month picker: tapping a bar
 * swaps the transaction list below to that month.
 */
@Composable
fun CategoryDetailScreen(category: String, onBack: () -> Unit) {
    val months = remember { (HISTORY_MONTHS - 1 downTo 0).map { YearMonth.now().minusMonths(it.toLong()) } }
    var history by remember { mutableStateOf<List<CategorySummaryItem?>?>(null) }
    var selected by rememberSaveable { mutableIntStateOf(HISTORY_MONTHS - 1) }
    var txs by remember { mutableStateOf<List<TransactionData>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val liveVersion = rememberTransactionsVersion()
    var openTx by remember { mutableStateOf<TxView?>(null) }

    LaunchedEffect(reloadKey, liveVersion) {
        history = SpendData.monthSummaries(months).map { (_, summary) ->
            summary?.categories?.firstOrNull { it.category.equals(category, ignoreCase = true) }
        }
    }
    LaunchedEffect(selected, reloadKey, liveVersion) {
        txs = null
        val month = months[selected]
        txs = runCatching {
            SpendData.transactions(month.atDay(1), minOf(month.atEndOfMonth(), LocalDate.now()), category = category)
        }.getOrDefault(emptyList())
    }

    val month = months[selected]
    val monthLong = month.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
    val accent = SmartSpendTheme.categories[category].accent

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item { ScreenHeader(title = category, subtitle = "Last $HISTORY_MONTHS months", onBack = onBack) }

            item {
                val h = history
                if (h == null) {
                    SkeletonBlocks(listOf(320.dp))
                } else {
                    val current = h[selected]
                    val spent = current?.total ?: 0.0
                    val previous = h.getOrNull(selected - 1)?.total
                    Block(modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MerchantAvatar(category = category, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Eyebrow("Spent in $monthLong")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(money(spent), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            comparison(spent, previous),
                            style = MaterialTheme.typography.bodySmall,
                            color = SmartSpendTheme.colors.inkMuted
                        )
                        val limit = current?.budget_limit ?: 0.0
                        if (limit > 0) {
                            Spacer(Modifier.height(12.dp))
                            val pct = spent / limit * 100
                            ThinProgress(fraction = (spent / limit).toFloat(), color = budgetColor(pct), height = 6.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${money(spent)} of ${money(limit)} budget",
                                style = MaterialTheme.typography.bodySmall,
                                color = SmartSpendTheme.colors.inkMuted
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        SpendBarChart(
                            bars = months.mapIndexed { i, m ->
                                ChartBar(
                                    label = m.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)),
                                    value = h[i]?.total ?: 0.0,
                                    detail = m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
                                )
                            },
                            selectedIndex = selected,
                            // The chart doubles as the month picker, so there is always a selection.
                            onSelect = { it?.let { i -> selected = i } },
                            plotHeight = 150.dp,
                            barColor = accent,
                            accessibilitySummary = "$category spending by month, last $HISTORY_MONTHS months"
                        )
                    }
                }
            }

            item {
                Text(
                    "Transactions in $monthLong",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp)
                )
            }
            val list = txs
            when {
                list == null -> item { SkeletonBlocks(listOf(200.dp)) }
                list.isEmpty() -> item { EmptyNote("Nothing in $monthLong", "Tap another month above.") }
                else -> transactionDays(list.map { it.toView() }, onClick = { openTx = it })
            }
        }
    }

    openTx?.let { tx ->
        TransactionSheet(tx = tx, onDismiss = { openTx = null }, onChanged = { reloadKey++ })
    }
}

private fun comparison(spent: Double, previous: Double?): String {
    if (previous == null) return "Tap a month to see its transactions"
    if (previous <= 0.0) return if (spent > 0) "Nothing the month before" else "Tap a month to see its transactions"
    val diff = spent - previous
    if (diff.absoluteValue < 1) return "Same as the month before"
    return "${money(diff)} ${if (diff > 0) "more" else "less"} than the month before"
}
