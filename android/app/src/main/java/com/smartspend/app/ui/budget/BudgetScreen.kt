package com.smartspend.app.ui.budget

import androidx.compose.animation.AnimatedVisibility
import com.smartspend.app.ui.components.rememberTransactionsVersion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.BudgetSetPayload
import com.smartspend.app.BudgetUtilizationData
import com.smartspend.app.OverallBudgetPayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.CategoryCache
import com.smartspend.app.ui.components.CategoryChip
import com.smartspend.app.ui.components.EmptyNote
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.MerchantAvatar
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.RoundIconButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SecondaryButton
import com.smartspend.app.ui.components.SectionTitle
import com.smartspend.app.ui.components.ThinProgress
import com.smartspend.app.ui.components.budgetColor
import com.smartspend.app.ui.components.budgetWord
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.monthName
import com.smartspend.app.ui.components.pickable
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Two things only: one overall monthly limit, and optional per-category limits. The two are
 * independent on the backend — category limits don't have to add up to the overall one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var loaded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val liveVersion = rememberTransactionsVersion()
    var savedOverall by remember { mutableStateOf<Double?>(null) }
    var overallInput by remember { mutableStateOf("") }
    var monthSpent by remember { mutableStateOf<Double?>(null) }
    var utilization by remember { mutableStateOf<List<BudgetUtilizationData>>(emptyList()) }
    var debitCategories by remember { mutableStateOf<List<String>>(emptyList()) }

    var formOpen by remember { mutableStateOf(false) }
    var formCategory by remember { mutableStateOf<String?>(null) }
    var formAmount by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey, liveVersion) {
        runCatching {
            coroutineScope {
                val overall = async { runCatching { RetrofitClient.apiService.getOverallBudget() }.getOrNull() }
                val util = async { RetrofitClient.apiService.getBudgetUtilization() }
                val home = async { runCatching { RetrofitClient.apiService.getHomeData("") }.getOrNull() }
                val cats = async { runCatching { CategoryCache.get() }.getOrNull() }

                val limit = overall.await()?.takeIf { it.isSuccessful }?.body()?.monthly_limit?.takeIf { it > 0 }
                savedOverall = limit
                if (!loaded) overallInput = limit?.let(::plainNumber).orEmpty()
                util.await().takeIf { it.isSuccessful }?.body()?.let { utilization = it.sortedByDescending { u -> u.percent_used } }
                monthSpent = home.await()?.body()?.overview?.total_spent
                cats.await()?.let { debitCategories = it.debit.pickable() }
            }
        }.onFailure { snackbar.showSnackbar(it.localizedMessage ?: "Couldn't load budgets") }
        loaded = true
    }

    fun saveOverall() {
        val value = overallInput.toDoubleOrNull()
        if (value == null || value <= 0) {
            scope.launch { snackbar.showSnackbar("Enter a monthly limit above ₹0") }
            return
        }
        saving = true
        scope.launch {
            val ok = runCatching { RetrofitClient.apiService.setOverallBudget(OverallBudgetPayload(value)).isSuccessful }
                .getOrDefault(false)
            saving = false
            if (ok) {
                savedOverall = value
                snackbar.showSnackbar("Monthly budget set to ${money(value)}")
            } else snackbar.showSnackbar("Couldn't save the budget")
        }
    }

    fun openForm(category: String?, limit: Double?) {
        formCategory = category
        formAmount = limit?.let(::plainNumber).orEmpty()
        formOpen = true
    }

    fun saveCategory() {
        val category = formCategory
        val value = formAmount.toDoubleOrNull()
        if (category == null) { scope.launch { snackbar.showSnackbar("Pick a category") }; return }
        if (value == null || value <= 0) { scope.launch { snackbar.showSnackbar("Enter a limit above ₹0") }; return }
        saving = true
        scope.launch {
            val ok = runCatching {
                RetrofitClient.apiService.setBudgetNoAuth(BudgetSetPayload(category = category, monthly_limit = value)).isSuccessful
            }.getOrDefault(false)
            saving = false
            if (ok) {
                formOpen = false
                reloadKey++
                snackbar.showSnackbar("$category limit set to ${money(value)}")
            } else snackbar.showSnackbar("Couldn't save the $category limit")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenHeader(
                title = "Budget plan",
                subtitle = "Limits for ${monthName(today.monthValue, today.year)}",
                onBack = onBack
            )

            // ── Overall ────────────────────────────────────────────────────
            SectionTitle("Overall budget", modifier = Modifier.padding(horizontal = ScreenGutter))
            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Eyebrow("Monthly limit")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("₹", style = MaterialTheme.typography.displaySmall, color = SmartSpendTheme.colors.inkMuted)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        if (overallInput.isEmpty()) {
                            Text("0", style = MaterialTheme.typography.displaySmall, color = SmartSpendTheme.colors.hairline)
                        }
                        BasicTextField(
                            value = overallInput,
                            onValueChange = { v -> overallInput = v.filter { it.isDigit() }.take(9) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Text(
                    "One cap for everything you spend in a month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartSpendTheme.colors.inkMuted
                )

                val limit = savedOverall
                val spent = monthSpent
                if (limit != null && spent != null) {
                    Spacer(Modifier.height(16.dp))
                    val pct = spent / limit * 100
                    ThinProgress(fraction = (spent / limit).toFloat(), color = budgetColor(pct))
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            "${money(spent)} spent so far",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${pct.roundToInt()}% · ${budgetWord(pct)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = budgetColor(pct)
                        )
                    }
                }

                val dirty = overallInput.toDoubleOrNull() != savedOverall && overallInput.isNotEmpty()
                if (dirty || savedOverall == null) {
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        label = if (savedOverall == null) "Set monthly budget" else "Update to ${overallInput.toDoubleOrNull()?.let(::money) ?: ""}",
                        onClick = { saveOverall() },
                        enabled = !saving && overallInput.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Per category ───────────────────────────────────────────────
            SectionTitle(
                "Category budgets",
                modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter - 4.dp, top = 12.dp)
            ) {
                RoundIconButton(
                    icon = if (formOpen) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (formOpen) "Close" else "Add category budget",
                    onClick = { if (formOpen) formOpen = false else openForm(null, null) }
                )
            }

            AnimatedVisibility(visible = formOpen) {
                Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                    Eyebrow("Category")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        debitCategories.forEach { name ->
                            CategoryChip(
                                category = name,
                                selected = name == formCategory,
                                onClick = {
                                    formCategory = name
                                    utilization.firstOrNull { it.category.equals(name, true) }?.let {
                                        formAmount = plainNumber(it.limit)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = formAmount,
                        onValueChange = { v -> formAmount = v.filter { it.isDigit() }.take(9) },
                        label = { Text("Monthly limit") },
                        prefix = { Text("₹") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Cancel", onClick = { formOpen = false }, modifier = Modifier.weight(1f))
                        PrimaryButton("Save", onClick = { saveCategory() }, enabled = !saving, modifier = Modifier.weight(1f))
                    }
                }
            }

            Block(
                modifier = Modifier.padding(horizontal = ScreenGutter),
                padding = PaddingValues(vertical = 4.dp)
            ) {
                if (loaded && utilization.isEmpty()) {
                    EmptyNote(
                        title = "No category limits yet",
                        body = "Tap + to cap a category like Food or Shopping."
                    )
                }
                utilization.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = SmartSpendTheme.colors.hairline)
                    CategoryBudgetRow(item, onClick = { openForm(item.category, item.limit) })
                }
            }
        }
    }
}

@Composable
private fun CategoryBudgetRow(item: BudgetUtilizationData, onClick: () -> Unit) {
    val pct = item.percent_used
    val color = budgetColor(pct)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MerchantAvatar(category = item.category, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.category,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("${pct.roundToInt()}% · ${budgetWord(pct)}", style = MaterialTheme.typography.labelLarge, color = color)
            }
            ThinProgress(fraction = (pct / 100).toFloat(), color = color, height = 6.dp)
            Text(
                if (item.spent > item.limit) "${money(item.spent - item.limit)} over ${money(item.limit)}"
                else "${money(item.spent)} of ${money(item.limit)}",
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkMuted
            )
        }
    }
}

private fun plainNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
