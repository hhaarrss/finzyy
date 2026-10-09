package com.smartspend.app.ui.auth

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch

/**
 * Collects the user's details after first sign-in ([editing] = false), or edits them from the
 * Account screen ([editing] = true). Only the name is required; the email is optional.
 */
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

    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameOk = name.trim().length >= 2
    val emailOk = email.isBlank() || Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    fun save() {
        showErrors = true
        if (!nameOk || !emailOk || saving) return
        saving = true
        error = null
        scope.launch {
            val payload = ProfilePayload(
                full_name = name.trim(),
                email = email.trim().takeIf { it.isNotEmpty() }
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
                        "Tell us your name. Your email is optional.",
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
}
