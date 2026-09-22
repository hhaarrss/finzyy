package com.smartspend.app.ui.trends

import androidx.activity.compose.BackHandler
import com.smartspend.app.ui.components.rememberTransactionsVersion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.smartspend.app.TransactionData
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.CategoryCache
import com.smartspend.app.ui.components.ChartBar
import com.smartspend.app.ui.components.CategoryChip
import com.smartspend.app.ui.components.Chip
import com.smartspend.app.ui.components.ChoiceChipRow
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.RoundIconButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SecondaryButton
import com.smartspend.app.ui.components.SegmentedControl
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.SmartSpendIcons
import com.smartspend.app.ui.components.SpendBarChart
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.TextAction
import com.smartspend.app.ui.components.isCredit
import com.smartspend.app.ui.components.isFromSms
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.parseTxDate
import com.smartspend.app.ui.components.pickable
import com.smartspend.app.ui.theme.SmartSpendTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private enum class TrendPeriod(val label: String) {
    Week("Last week"),
    TwoWeeks("Last 2 weeks"),
    Month("Last month"),
    ThreeMonths("Last 3 months"),
    SixMonths("Last 6 months"),
    Year("Last year");

    val start: LocalDate
        get() {
            val today = LocalDate.now()
            return when (this) {
                Week -> today.minusDays(6)
                TwoWeeks -> today.minusDays(13)
                Month -> today.minusDays(29)
                ThreeMonths -> today.minusWeeks(12).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                SixMonths -> YearMonth.from(today).minusMonths(5).atDay(1)
                Year -> YearMonth.from(today).minusMonths(11).atDay(1)
            }
        }

    val bucket: Bucket
        get() = when (this) {
            Week, TwoWeeks, Month -> Bucket.Day
            ThreeMonths -> Bucket.Week
            SixMonths, Year -> Bucket.Month
        }
}

private enum class Bucket(val per: String) { Day("day"), Week("week"), Month("month") }

private enum class Flow(val label: String) { Spending("Spending"), Income("Income") }

private enum class Source(val label: String) { All("All"), Sms("From SMS"), Manual("Added by me") }

private data class TrendFilters(
    val flow: Flow = Flow.Spending,
    val categories: Set<String> = emptySet(),
    val source: Source = Source.All,
    val includeTransfers: Boolean = false
) {
    val activeCount: Int
        get() = listOf(flow != Flow.Spending, categories.isNotEmpty(), source != Source.All, includeTransfers).count { it }
}

@Composable
fun TrendsScreen(onBack: () -> Unit, onBudget: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(TrendPeriod.Month) }
    var filters by remember { mutableStateOf(TrendFilters()) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var txs by remember { mutableStateOf<List<TransactionData>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val liveVersion = rememberTransactionsVersion()
    var selected by remember(period, filters) { mutableStateOf<Int?>(null) }

    // One fetch per period; filters apply locally so Apply is instant.
    LaunchedEffect(period, reloadKey, liveVersion) {
        error = null
        txs = try {
            SpendData.transactions(period.start, LocalDate.now(), includeTransfers = true)
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Can't reach SmartSpend right now."
            null
        }
    }

    val bars = remember(txs, filters, period) {
        txs?.let { list -> buildBars(list.filter { matches(it, filters) }, period) }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ScreenHeader(title = "Trends", onBack = onBack) {
                    RoundIconButton(
                        icon = SmartSpendIcons.Filter,
                        contentDescription = if (filters.activeCount > 0) "Filters, ${filters.activeCount} active" else "Filters",
                        onClick = { filterOpen = true },
                        showDot = filters.activeCount > 0
                    )
                }

                ChoiceChipRow(
                    options = TrendPeriod.entries,
                    selected = period,
                    label = { it.label },
                    onSelect = { period = it }
                )

                if (filters.activeCount > 0) {
                    ActiveFilterLine(filters, onClear = { filters = TrendFilters() })
                }

                when {
                    error != null && txs == null -> ErrorPanel(error.orEmpty(), onRetry = { reloadKey++ })
                    bars == null -> SkeletonBlocks(listOf(330.dp))
                    else -> ChartBlock(
                        bars = bars,
                        period = period,
                        filters = filters,
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }

                Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                    Text(
                        "Want a ceiling on this?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "A monthly budget turns this chart into a pace check.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartSpendTheme.colors.inkMuted
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Set monthly budget", onClick = onBudget, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        AnimatedVisibility(
            visible = filterOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(onClick = { filterOpen = false }, indication = null, interactionSource = null)
            )
        }
        AnimatedVisibility(
            visible = filterOpen,
            enter = slideInHorizontally(tween(280)) { it },
            exit = slideOutHorizontally(tween(240)) { it }
        ) {
            FilterPanel(
                initial = filters,
                onApply = { filters = it; filterOpen = false },
                onClose = { filterOpen = false }
            )
        }
    }

    if (filterOpen) BackHandler { filterOpen = false }
}

@Composable
private fun ActiveFilterLine(filters: TrendFilters, onClear: () -> Unit) {
    val parts = buildList {
        if (filters.flow == Flow.Income) add("Income")
        if (filters.categories.isNotEmpty()) {
            add(filters.categories.take(2).joinToString() + if (filters.categories.size > 2) " +${filters.categories.size - 2}" else "")
        }
        if (filters.source != Source.All) add(filters.source.label)
        if (filters.includeTransfers) add("with transfers")
    }
    Row(
        modifier = Modifier.padding(start = ScreenGutter + 4.dp, end = ScreenGutter - 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Filtered: " + parts.joinToString(" · "),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = SmartSpendTheme.colors.inkMuted
        )
        TextAction("Clear", onClick = onClear)
    }
}

@Composable
private fun ChartBlock(
    bars: List<ChartBar>,
    period: TrendPeriod,
    filters: TrendFilters,
    selected: Int?,
    onSelect: (Int?) -> Unit
) {
    val total = bars.sumOf { it.value }
    val average = if (bars.isNotEmpty()) total / bars.size else 0.0
    val peakIndex = bars.indices.maxByOrNull { bars[it].value }
    val verb = if (filters.flow == Flow.Income) "Received" else "Spent"

    Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
        val sel = selected?.let { bars.getOrNull(it) }
        Eyebrow(if (sel != null) "$verb · ${sel.detail}" else "$verb · ${period.label.lowercase(Locale.ENGLISH)}")
        Text(
            money(sel?.value ?: total),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            if (sel != null) "Tap the bar again to see the whole period"
            else "About ${money(average)} a ${period.bucket.per} · tap a bar for detail",
            style = MaterialTheme.typography.bodySmall,
            color = SmartSpendTheme.colors.inkMuted
        )
        Spacer(Modifier.height(18.dp))
        if (total <= 0) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filters.activeCount > 0) "Nothing matches these filters in this period" else "Nothing recorded in this period",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartSpendTheme.colors.inkMuted
                )
            }
        } else {
            SpendBarChart(
                bars = bars,
                selectedIndex = selected,
                onSelect = onSelect,
                labelEvery = when (bars.size) {
                    in 0..8 -> 1
                    in 9..14 -> 2
                    in 15..20 -> 4
                    else -> 5
                },
                barColor = if (filters.flow == Flow.Income) SmartSpendTheme.colors.positive else MaterialTheme.colorScheme.primary,
                accessibilitySummary = "$verb ${money(total)} over ${period.label.lowercase(Locale.ENGLISH)}"
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = SmartSpendTheme.colors.hairline)
            Spacer(Modifier.height(14.dp))
            Row {
                Stat("Highest ${period.bucket.per}", peakIndex?.let { bars[it].detail } ?: "—", Modifier.weight(1f))
                Stat("Amount", peakIndex?.let { money(bars[it].value) } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(caption)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Full-height panel that slides in from the right. Edits a draft; nothing changes on the
 * chart until Apply, so users can explore choices without the chart jumping behind them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    initial: TrendFilters,
    onApply: (TrendFilters) -> Unit,
    onClose: () -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var lists by remember { mutableStateOf(CategoryCache.lists) }
    LaunchedEffect(Unit) { if (lists == null) lists = runCatching { CategoryCache.get() }.getOrNull() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ScreenGutter, end = ScreenGutter - 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            RoundIconButton(icon = Icons.Default.Close, contentDescription = "Close filters", onClick = onClose)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FilterSection("Show") {
                SegmentedControl(
                    options = Flow.entries.map { it.label },
                    selectedIndex = draft.flow.ordinal,
                    onSelect = { i ->
                        val flow = Flow.entries[i]
                        if (flow != draft.flow) draft = draft.copy(flow = flow, categories = emptySet())
                    }
                )
            }

            FilterSection(
                "Categories",
                hint = if (draft.categories.isEmpty()) "All categories" else "${draft.categories.size} selected"
            ) {
                val options = lists?.let { if (draft.flow == Flow.Income) it.credit else it.debit }?.pickable()
                if (options == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = SmartSpendTheme.colors.inkMuted)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("All", selected = draft.categories.isEmpty(), onClick = { draft = draft.copy(categories = emptySet()) })
                        options.forEach { name ->
                            CategoryChip(
                                category = name,
                                selected = name in draft.categories,
                                onClick = {
                                    draft = draft.copy(
                                        categories = if (name in draft.categories) draft.categories - name else draft.categories + name
                                    )
                                }
                            )
                        }
                    }
                }
            }

            FilterSection("Source") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Source.entries.forEach { s ->
                        Chip(s.label, selected = draft.source == s, onClick = { draft = draft.copy(source = s) })
                    }
                }
            }

            if (draft.flow == Flow.Spending) {
                Block(
                    padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    onClick = { draft = draft.copy(includeTransfers = !draft.includeTransfers) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include transfers", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Money sent to people or your own accounts",
                                style = MaterialTheme.typography.bodySmall,
                                color = SmartSpendTheme.colors.inkMuted
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = draft.includeTransfers, onCheckedChange = { draft = draft.copy(includeTransfers = it) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenGutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton("Clear all", onClick = { draft = TrendFilters() }, modifier = Modifier.weight(1f))
            PrimaryButton(
                if (draft.activeCount > 0) "Apply (${draft.activeCount})" else "Apply",
                onClick = { onApply(draft) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

private fun matches(tx: TransactionData, f: TrendFilters): Boolean {
    if (f.flow == Flow.Spending && tx.isCredit) return false
    if (f.flow == Flow.Income && !tx.isCredit) return false
    val transfer = tx.is_transfer || tx.category.equals("Transfer", ignoreCase = true)
    if (f.flow == Flow.Spending && transfer && !f.includeTransfers) return false
    if (f.categories.isNotEmpty() && f.categories.none { it.equals(tx.category, ignoreCase = true) }) return false
    return when (f.source) {
        Source.All -> true
        Source.Sms -> tx.isFromSms
        Source.Manual -> !tx.isFromSms
    }
}

/** Bucket keys carry the bucket's first day, so labels and "Highest day/week/month" read naturally. */
private fun buildBars(txs: List<TransactionData>, period: TrendPeriod): List<ChartBar> {
    val start = period.start
    val end = LocalDate.now()
    val keys: List<LocalDate> = when (period.bucket) {
        Bucket.Day -> (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
        Bucket.Week -> generateSequence(start) { it.plusWeeks(1).takeIf { n -> !n.isAfter(end) } }.toList()
        Bucket.Month -> generateSequence(YearMonth.from(start)) { it.plusMonths(1).takeIf { n -> !n.isAfter(YearMonth.from(end)) } }
            .map { it.atDay(1) }.toList()
    }
    val totals = HashMap<LocalDate, Double>()
    txs.forEach { tx ->
        val d = parseTxDate(tx.date) ?: return@forEach
        if (d.isBefore(start) || d.isAfter(end)) return@forEach
        val key = when (period.bucket) {
            Bucket.Day -> d
            Bucket.Week -> start.plusWeeks(ChronoUnit.WEEKS.between(start, d))
            Bucket.Month -> d.withDayOfMonth(1)
        }
        totals[key] = (totals[key] ?: 0.0) + tx.amount
    }
    return keys.map { key ->
        val label = when (period.bucket) {
            Bucket.Day -> if (period == TrendPeriod.Week) key.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
            else key.dayOfMonth.toString()
            Bucket.Week -> key.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
            Bucket.Month -> key.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
        }
        val detail = when (period.bucket) {
            Bucket.Day -> key.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
            Bucket.Week -> "Week of " + key.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
            Bucket.Month -> key.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        }
        ChartBar(label, totals[key] ?: 0.0, detail)
    }
}
