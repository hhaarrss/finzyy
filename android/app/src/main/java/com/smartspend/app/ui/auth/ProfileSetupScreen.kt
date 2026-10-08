package com.smartspend.app.ui.auth

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.AuthSession
import com.smartspend.app.ProfilePayload
import com.smartspend.app.RetrofitClient
import com.smartspend.app.UserData
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.Chip
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GENDERS = listOf(
    "male" to "Male",
    "female" to "Female",
    "other" to "Other",
    "prefer_not_to_say" to "Prefer not to say"
)

private val OCCUPATIONS = listOf(
    "Salaried", "Self-employed", "Business owner", "Student", "Homemaker", "Retired", "Other"
)

private const val MIN_AGE_YEARS = 13L

/**
 * Collects the user's details after first sign-in ([editing] = false), or edits them from the
 * Account screen ([editing] = true). Only the name is required; the monthly budget, if given,
 * becomes the overall budget on the Budget screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileSetupScreen(
    initial: UserData?,
    editing: Boolean,
    onSaved: (UserData) -> Unit,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable(initial) { mutableStateOf(initial?.full_name.orEmpty()) }
    var email by rememberSaveable(initial) { mutableStateOf(initial?.email.orEmpty()) }
    var dob by rememberSaveable(initial) { mutableStateOf(initial?.date_of_birth) }
    var gender by rememberSaveable(initial) { mutableStateOf(initial?.gender) }
    var city by rememberSaveable(initial) { mutableStateOf(initial?.city.orEmpty()) }
    var occupation by rememberSaveable(initial) { mutableStateOf(initial?.occupation) }
    var income by rememberSaveable(initial) { mutableStateOf(initial?.monthly_income?.let(::plainAmount).orEmpty()) }
    var budget by rememberSaveable(initial) { mutableStateOf(initial?.monthly_budget?.let(::plainAmount).orEmpty()) }

    var pickingDob by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameOk = name.trim().length >= 2
    val emailOk = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val incomeOk = income.isBlank() || income.toDoubleOrNull()?.let { it >= 0 } == true
    val budgetOk = budget.isBlank() || budget.toDoubleOrNull()?.let { it > 0 } == true

    fun save() {
        showErrors = true
        if (!nameOk || !emailOk || !incomeOk || !budgetOk || saving) return
        saving = true
        error = null
        scope.launch {
            val payload = ProfilePayload(
                full_name = name.trim(),
                email = email.trim().takeIf { it.isNotEmpty() },
                date_of_birth = dob,
                gender = gender,
                city = city.trim().takeIf { it.isNotEmpty() },
                occupation = occupation,
                monthly_income = income.toDoubleOrNull(),
                monthly_budget = budget.toDoubleOrNull()
            )
            val response = runCatching { RetrofitClient.apiService.updateProfile(payload) }.getOrNull()
            val body = response?.body()
            when {
                response == null -> error = "Can't reach Finzyy. Check your internet and try again."
                response.isSuccessful && body != null -> {
                    AuthSession.update(context, body)
                    onSaved(body)
                }
                else -> error = serverMessage(response, "Couldn't save your profile (${response.code()}).")
            }
            saving = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        if (editing) {
            ScreenHeader(title = "Edit profile", onBack = onBack)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!editing) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)) {
                    Text(
                        "Set up your profile",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A few details help Finzyy tailor your budgets and insights. Only your name is required.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SmartSpendTheme.colors.inkMuted
                    )
                }
            }

            // ── Identity ─────────────────────────────────────────────
            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(name = name, size = 60)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name.trim().ifEmpty { "Your name" },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val phone = initial?.phone_number
                        if (phone != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatIndianNumber(phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SmartSpendTheme.colors.inkMuted
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = SmartSpendTheme.colors.positive,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Personal details ─────────────────────────────────────
            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Eyebrow("Personal details")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Full name *") },
                    singleLine = true,
                    isError = showErrors && !nameOk,
                    supportingText = if (showErrors && !nameOk) ({ Text("Enter your name") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().take(255) },
                    label = { Text("Email") },
                    placeholder = { Text("you@example.com") },
                    singleLine = true,
                    isError = showErrors && !emailOk,
                    supportingText = { Text(if (showErrors && !emailOk) "Enter a valid email" else "For monthly reports and account recovery") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(4.dp))
                ClickableField(
                    value = dob?.let(::displayDate).orEmpty(),
                    label = "Date of birth",
                    onClick = { pickingDob = true }
                )
                Spacer(Modifier.height(16.dp))
                Text("Gender", style = MaterialTheme.typography.labelLarge, color = SmartSpendTheme.colors.inkMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GENDERS.forEach { (value, label) ->
                        Chip(label = label, selected = gender == value, onClick = { gender = if (gender == value) null else value })
                    }
                }
            }

            // ── Work & location ──────────────────────────────────────
            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Eyebrow("Work & location")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it.take(100) },
                    label = { Text("City") },
                    placeholder = { Text("e.g. Pune") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(16.dp))
                Text("Occupation", style = MaterialTheme.typography.labelLarge, color = SmartSpendTheme.colors.inkMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OCCUPATIONS.forEach { option ->
                        Chip(label = option, selected = occupation == option, onClick = { occupation = if (occupation == option) null else option })
                    }
                }
            }

            // ── Money ────────────────────────────────────────────────
            Block(modifier = Modifier.padding(horizontal = ScreenGutter)) {
                Eyebrow("Money")
                Spacer(Modifier.height(14.dp))
                AmountField(
                    value = income,
                    onValueChange = { income = it },
                    label = "Monthly income",
                    supporting = if (showErrors && !incomeOk) "Enter an amount" else "Take-home pay. Used to show how much you save.",
                    isError = showErrors && !incomeOk
                )
                Spacer(Modifier.height(4.dp))
                AmountField(
                    value = budget,
                    onValueChange = { budget = it },
                    label = "Monthly spending budget",
                    supporting = if (showErrors && !budgetOk) "Enter an amount above ₹0" else "Sets up your Budget screen. You can change it any time.",
                    isError = showErrors && !budgetOk
                )
            }

            Text(
                "Your details are private to your account and never shared.",
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkFaint,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            ErrorText(error)
            if (error != null) Spacer(Modifier.height(8.dp))
            LoadingButton(
                label = if (editing) "Save changes" else "Save and continue",
                onClick = ::save,
                loading = saving
            )
        }
    }

    if (pickingDob) {
        val latest = LocalDate.now().minusYears(MIN_AGE_YEARS)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (dob?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: latest.minusYears(12))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1920..latest.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= latest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDob = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    pickingDob = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDob = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    isError: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            // Digits and one decimal point, two decimals max.
            val cleaned = raw.filter { it.isDigit() || it == '.' }
            val parts = cleaned.split('.')
            val normalized = if (parts.size > 1) parts[0] + "." + parts.drop(1).joinToString("").take(2) else cleaned
            onValueChange(normalized.take(12))
        },
        label = { Text(label) },
        prefix = { Text("₹ ") },
        singleLine = true,
        isError = isError,
        supportingText = { Text(supporting) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    )
}

/** Read-only field that opens a picker when tapped. */
@Composable
private fun ClickableField(value: String, label: String, onClick: () -> Unit) {
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Select") },
            trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        )
        // Covers the field so the tap opens the picker instead of focusing the text field.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        )
    }
}

private fun displayDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())) }
        .getOrDefault(iso)

private fun plainAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.ROOT, value)
