package com.smartspend.app.ui.insights

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.HomeData
import com.smartspend.app.InsightsSummaryData
import com.smartspend.app.RetrofitClient
import com.smartspend.app.SpendingChangeItem
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.MerchantAvatar
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SectionTitle
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.TextAction
import com.smartspend.app.ui.components.ThinProgress
import com.smartspend.app.ui.components.budgetColor
import com.smartspend.app.ui.components.budgetWord
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.monthName
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.TabularAmount
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private data class InsightsBundle(val summary: InsightsSummaryData, val home: HomeData?, val overallBudget: Double?)

/**
 * Answers, in order: am I on course this month, which budgets need me, what changed, what
 * keeps recurring, and what looked unusual. Every figure comes from /insights/summary and
 * /home — this screen phrases them, it doesn't compute new aggregates.
 */
@Composable
fun InsightsScreen(onBack: () -> Unit, onBudget: () -> Unit, onCategory: (String) -> Unit) {
    var bundle by remember { mutableStateOf<InsightsBundle?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        error = null
        try {
            coroutineScope {
                val summary = async { RetrofitClient.apiService.getInsightsSummaryNoAuth() }
                val home = async { runCatching { RetrofitClient.apiService.getHomeData("") }.getOrNull() }
                val overall = async { runCatching { RetrofitClient.apiService.getOverallBudget() }.getOrNull() }
                val s = summary.await()
                val body = s.body()
                if (!s.isSuccessful || body == null) {
                    error = "Insights didn't load (${s.code()})."
                } else {
                    bundle = InsightsBundle(
                        summary = body,
                        home = home.await()?.body(),
                        overallBudget = overall.await()?.takeIf { it.isSuccessful }?.body()?.monthly_limit?.takeIf { it > 0 }
                    )
                }
            }
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Can't reach SmartSpend right now."
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        val b = bundle
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val today = LocalDate.now()
                ScreenHeader(title = "Insights", subtitle = monthName(today.monthValue, today.year) + " so far", onBack = onBack)
            }
            when {
                error != null && b == null -> item { ErrorPanel(error.orEmpty(), onRetry = { reloadKey++ }) }
                b == null -> item { SkeletonBlocks(listOf(210.dp, 160.dp, 200.dp)) }
                else -> {
                    b.home?.let { home -> item { PaceBlock(home, b.overallBudget, onBudget) } }

                    val alerts = b.summary.budget_alerts.orEmpty()
                    item { SectionHeading("Budgets needing attention", if (alerts.isNotEmpty()) "Manage" else null, onBudget) }
                    item {
                        Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(vertical = 4.dp)) {
                            if (alerts.isEmpty()) {
                                Quiet("Every category budget is on track.")
                            } else {
                                alerts.forEachIndexed { i, raw ->
                                    if (i > 0) Divider()
                                    AlertRow(raw)
                                }
                            }
                        }
                    }

                    val movers = movers(b.summary.spending_changes.orEmpty())
                    item { SectionHeading("What changed vs last month") }
                    item {
                        Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(vertical = 4.dp)) {
                            if (movers.isEmpty()) {
                                Quiet("Not enough history yet to compare months.")
                            } else {
                                movers.forEachIndexed { i, item ->
                                    if (i > 0) Divider()
                                    MoverRow(item, onClick = { onCategory(item.category) })
                                }
                            }
                        }
                    }

                    val recurring = b.summary.recurring.orEmpty()
                    item { SectionHeading("Recurring payments") }
                    item {
                        Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(vertical = 4.dp)) {
                            if (recurring.isEmpty()) {
                                Quiet("No subscriptions or repeat payments spotted yet.")
                            } else {
                                val monthly = recurring.sumOf { monthlyEquivalent(it) }
                                Text(
                                    "About ${money(monthly)} a month goes to repeat payments.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                                recurring.forEach { raw ->
                                    Divider()
                                    RecurringRow(raw)
                                }
                            }
                        }
                    }

                    val spikes = b.summary.anomalies.orEmpty().filter { it["kind"]?.toString() != "budget" }
                    if (spikes.isNotEmpty()) {
                        item { SectionHeading("Unusual spikes") }
                        item {
                            Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(vertical = 4.dp)) {
                                spikes.forEachIndexed { i, raw ->
                                    if (i > 0) Divider()
                                    SpikeRow(raw)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The month as a pace problem: where you are, where a straight line puts you at month end,
 * and — with a budget — what you can spend per day from here and still land inside it.
 */
@Composable
private fun PaceBlock(home: HomeData, budget: Double?, onBudget: () -> Unit) {
    val today = LocalDate.now()
    val ym = YearMonth.of(home.year, home.month)
    val isCurrent = ym == YearMonth.from(today)
    val daysIn = ym.lengthOfMonth()
    val elapsed = if (isCurrent) today.dayOfMonth else daysIn
    val daysLeft = daysIn - elapsed
    val spent = home.overview.total_spent
    val projected = if (elapsed > 0) spent / elapsed * daysIn else spent

    Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
        Eyebrow("Spent so far")
        Text(money(spent), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "${money(spent / elapsed.coerceAtLeast(1))} a day on average · day $elapsed of $daysIn",
            style = MaterialTheme.typography.bodySmall,
            color = SmartSpendTheme.colors.inkMuted
        )
        Spacer(Modifier.height(16.dp))

        if (budget != null) {
            val pct = spent / budget * 100
            ThinProgress(fraction = (spent / budget).toFloat(), color = budgetColor(pct), height = 10.dp)
            Spacer(Modifier.height(8.dp))
            Row {
                Text("${pct.roundToInt()}% of ${money(budget)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(budgetWord(pct), style = MaterialTheme.typography.labelLarge, color = budgetColor(pct))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = SmartSpendTheme.colors.hairline)
            Spacer(Modifier.height(16.dp))
            Row {
                PaceFact(
                    caption = "Projected",
                    value = money(projected),
                    note = if (projected > budget) "${money(projected - budget)} over" else "${money(budget - projected)} under",
                    noteColor = if (projected > budget) SmartSpendTheme.colors.negative else SmartSpendTheme.colors.positive,
                    modifier = Modifier.weight(1f)
                )
                val remaining = budget - spent
                PaceFact(
                    caption = "Safe to spend",
                    value = if (remaining > 0 && daysLeft > 0) money(remaining / daysLeft) + "/day" else money(0.0),
                    note = if (remaining > 0) "for the next $daysLeft days" else "budget used up",
                    noteColor = SmartSpendTheme.colors.inkMuted,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Text(
                "At this pace you'll spend about ${money(projected)} by ${ym.atEndOfMonth().dayOfMonth} ${monthName(home.month, home.year, "MMM")}.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Set a monthly budget and this becomes a daily spending limit.",
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkMuted
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Set monthly budget", onClick = onBudget, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PaceFact(caption: String, value: String, note: String, noteColor: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Eyebrow(caption)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(note, style = MaterialTheme.typography.bodySmall, color = noteColor)
    }
}

@Composable
private fun SectionHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    SectionTitle(title, modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 10.dp)) {
        if (action != null) TextAction(action, onClick = onAction)
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = SmartSpendTheme.colors.inkMuted
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = SmartSpendTheme.colors.hairline)

@Composable
private fun AlertRow(raw: Map<String, Any>) {
    val category = raw["category"]?.toString() ?: "Category"
    val spent = num(raw["spent"])
    val limit = num(raw["limit"])
    val pct = num(raw["percent"]).takeIf { it > 0 } ?: if (limit > 0) spent / limit * 100 else 0.0
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantAvatar(category = category, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Text(category, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("${pct.roundToInt()}% · ${budgetWord(pct)}", style = MaterialTheme.typography.labelLarge, color = budgetColor(pct))
        }
        ThinProgress(fraction = (pct / 100).toFloat(), color = budgetColor(pct), height = 6.dp)
        Text(
            if (spent > limit) "${money(spent - limit)} over the ${money(limit)} limit" else "${money(limit - spent)} left of ${money(limit)}",
            style = MaterialTheme.typography.bodySmall,
            color = SmartSpendTheme.colors.inkMuted
        )
    }
}

@Composable
private fun MoverRow(item: SpendingChangeItem, onClick: () -> Unit) {
    val up = item.direction == "up"
    val pct = item.change_percent ?: 0.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantAvatar(category = item.category, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(item.category, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "${if (up) "▲" else "▼"} ${pct.roundToInt()}%",
            style = MaterialTheme.typography.titleMedium.merge(TabularAmount),
            color = if (up) SmartSpendTheme.colors.negative else SmartSpendTheme.colors.positive
        )
        Spacer(Modifier.width(6.dp))
        Text(if (up) "more" else "less", style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted)
    }
}

@Composable
private fun RecurringRow(raw: Map<String, Any>) {
    val merchant = raw["merchant"]?.toString() ?: "Unknown"
    val frequency = raw["frequency"]?.toString()?.replaceFirstChar { it.titlecase(Locale.ENGLISH) } ?: "Recurring"
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MerchantAvatar(category = null, merchant = merchant, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(merchant, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(frequency, style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted)
        }
        Text(money(num(raw["amount"])), style = MaterialTheme.typography.titleMedium.merge(TabularAmount), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SpikeRow(raw: Map<String, Any>) {
    val category = raw["category"]?.toString() ?: raw["merchant"]?.toString()?.substringBefore(" spending") ?: "Spending"
    val amount = num(raw["amount"])
    val avg = num(raw["avg"])
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MerchantAvatar(category = category, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(category, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (avg > 0) "${money(amount)} vs your usual ${money(avg)}" else money(amount),
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkMuted
            )
        }
        if (avg > 0) {
            Text(
                "${(amount / avg).let { if (it >= 10) it.roundToInt().toString() else "%.1f".format(it) }}×",
                style = MaterialTheme.typography.titleMedium,
                color = SmartSpendTheme.colors.caution
            )
        }
    }
}

/** Biggest real month-over-month moves; the backend already withholds %s on tiny baselines. */
private fun movers(items: List<SpendingChangeItem>): List<SpendingChangeItem> =
    items.filter { !it.not_enough_data && it.change_percent != null && it.change_percent.absoluteValue in 1.0..999.0 }
        .sortedByDescending { it.change_percent?.absoluteValue ?: 0.0 }
        .take(5)

private fun monthlyEquivalent(raw: Map<String, Any>): Double {
    val amount = num(raw["amount"])
    return when (raw["frequency"]?.toString()?.lowercase(Locale.ROOT)) {
        "weekly" -> amount * 52 / 12
        "yearly", "annual", "annually" -> amount / 12
        "quarterly" -> amount / 3
        else -> amount
    }
}

private fun num(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
}
