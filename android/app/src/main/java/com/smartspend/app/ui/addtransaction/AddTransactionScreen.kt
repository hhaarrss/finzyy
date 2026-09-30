package com.smartspend.app.ui.addtransaction

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartspend.app.CategoryListsResponse
import com.smartspend.app.RetrofitClient
import com.smartspend.app.TransactionCreatePayload
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.CategoryCache
import com.smartspend.app.ui.components.CategoryChip
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SecondaryButton
import com.smartspend.app.ui.components.SegmentedControl
import com.smartspend.app.ui.components.dayHeader
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.pickable
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTransactionScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val amountFocus = remember { FocusRequester() }

    var lists by remember { mutableStateOf<CategoryListsResponse?>(CategoryCache.lists) }
    var isCredit by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var payee by rememberSaveable { mutableStateOf("") }
    var dateEpochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SavedTx?>(null) }
    var pendingAddAnother by remember { mutableStateOf(false) }

    val date = LocalDate.ofEpochDay(dateEpochDay)
    val amountValue = amount.toDoubleOrNull()
    val amountOk = amountValue != null && amountValue > 0
    val payeeOk = payee.isNotBlank()
    val categoryOk = category != null

    LaunchedEffect(Unit) {
        amountFocus.requestFocus()
        if (lists == null) lists = runCatching { CategoryCache.get() }.getOrNull()
    }

    fun resetForNext() {
        amount = ""
        payee = ""
        notes = ""
        showErrors = false
        amountFocus.requestFocus()
    }

    fun save(addAnother: Boolean) {
        showErrors = true
        if (!amountOk || !payeeOk || !categoryOk) {
            scope.launch { snackbar.showSnackbar("Fill in the highlighted fields") }
            return
        }
        saving = true
        scope.launch {
            val payload = TransactionCreatePayload(
                amount = amountValue!!,
                type = if (isCredit) "credit" else "debit",
                category = category!!,
                merchant = payee.trim(),
                date = "${date}T12:00:00Z",
                notes = notes.trim().ifEmpty { null }
            )
            val outcome = submitTransaction(payload, date)
            saving = false
            when (outcome) {
                SaveOutcome.Saved -> {
                    pendingAddAnother = addAnother
                    saved = SavedTx(payload.amount, isCredit, payload.category, payload.merchant.orEmpty())
                }
                is SaveOutcome.Failed -> snackbar.showSnackbar(outcome.message)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = ScreenGutter, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton("Save & add another", onClick = { save(true) }, enabled = !saving && saved == null, modifier = Modifier.weight(1.25f))
                    PrimaryButton(if (saving) "Saving…" else "Save", onClick = { save(false) }, enabled = !saving && saved == null, modifier = Modifier.weight(1f))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenHeader(title = "Add transaction", onBack = onBack)

            SegmentedControl(
                options = listOf("Spent", "Received"),
                selectedIndex = if (isCredit) 1 else 0,
                onSelect = {
                    val credit = it == 1
                    if (credit != isCredit) {
                        isCredit = credit
                        category = null
                    }
                },
                modifier = Modifier.padding(horizontal = ScreenGutter)
            )

            // Amount — the one field everything else hangs off, so it gets the display size.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenGutter, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "₹",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (amount.isEmpty()) SmartSpendTheme.colors.hairline else MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = amount,
                        onValueChange = { v -> amount = sanitiseAmount(v) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.displayLarge.copy(
                            color = if (isCredit) SmartSpendTheme.colors.positive else MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Start
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier
                            .width(amountFieldWidth(amount))
                            .focusRequester(amountFocus),
                        decorationBox = { inner ->
                            if (amount.isEmpty()) {
                                Text("0", style = MaterialTheme.typography.displayLarge, color = SmartSpendTheme.colors.hairline)
                            }
                            inner()
                        }
                    )
                }
                if (showErrors && !amountOk) {
                    Text("Enter an amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                OutlinedTextField(
                    value = payee,
                    onValueChange = { payee = it },
                    label = { Text(if (isCredit) "Received from" else "Paid to") },
                    placeholder = { Text(if (isCredit) "e.g. Acme Pvt Ltd" else "e.g. Swiggy, Rahul, landlord") },
                    isError = showErrors && !payeeOk,
                    supportingText = if (showErrors && !payeeOk) ({ Text("Required") }) else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(12.dp))
                DateField(label = dayHeader(date), onClick = { pickingDate = true })
            }

            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Category", modifier = Modifier.weight(1f))
                    if (showErrors && !categoryOk) {
                        Text("Pick one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val options = lists?.let { if (isCredit) it.credit else it.debit }?.pickable()
                if (options == null) {
                    Text("Loading categories…", style = MaterialTheme.typography.bodyMedium, color = SmartSpendTheme.colors.inkMuted)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { name ->
                            CategoryChip(category = name, selected = name == category, onClick = { category = name })
                        }
                    }
                }
            }

            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("What was it for?") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        dateEpochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    pickingDate = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }

    saved?.let { tx ->
        SaveSuccessPopup(saved = tx, onDone = {
            saved = null
            if (pendingAddAnother) resetForNext() else onBack()
        })
    }
}

@Composable
internal fun DateField(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(1.dp, SmartSpendTheme.colors.hairline, MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Date", style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(Icons.Default.DateRange, contentDescription = "Change date", tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
    }
}

/** Digits and one decimal point, at most two decimals — anything else a keyboard can emit is dropped. */
private fun sanitiseAmount(raw: String): String {
    val cleaned = raw.filter { it.isDigit() || it == '.' }
    val firstDot = cleaned.indexOf('.')
    val single = if (firstDot < 0) cleaned else cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
    val parts = single.split('.')
    val whole = parts[0].take(8)
    return if (parts.size > 1) whole + "." + parts[1].take(2) else whole
}

/** The amount field grows with its content so the ₹ sign stays snug against the digits. */
private fun amountFieldWidth(amount: String) = (maxOf(1, amount.length) * 34 + 8).dp
