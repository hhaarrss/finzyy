package com.smartspend.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.smartspend.app.AuthResponse
import com.smartspend.app.AuthSession
import com.smartspend.app.FirebaseTokenPayload
import com.smartspend.app.R
import com.smartspend.app.RetrofitClient
import com.smartspend.app.UserData
import com.smartspend.app.ui.components.SecondaryButton
import com.smartspend.app.ui.permission.findComponentActivity
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.Response

private enum class SignInMode { Phone, Email }

/**
 * Sign in / sign up: mobile number + OTP first, Google as the alternative, and email + password
 * only for accounts created before phone sign-in. New and existing users go through the same
 * steps — the backend creates the account on first sign-in.
 */
@Composable
fun SignInScreen(
    onSignedIn: (AuthResponse) -> Unit,
    onGoogle: () -> Unit,
    googleBusy: Boolean,
    googleError: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(SignInMode.Phone) }

    val verifier = rememberPhoneVerifier { idToken ->
        val response = runCatching { RetrofitClient.apiService.firebaseLogin(FirebaseTokenPayload(idToken)) }
            .getOrElse { return@rememberPhoneVerifier "Can't reach Finzyy. Check your internet and try again." }
        val body = response.body()
        if (response.isSuccessful && body != null) {
            AuthSession.save(context, body)
            onSignedIn(body)
            null
        } else {
            serverMessage(response, fallback = "Sign-in failed (${response.code()}). Please try again.")
        }
    }

    when {
        mode == SignInMode.Email -> EmailSignIn(onSignedIn = onSignedIn, onBack = { mode = SignInMode.Phone })
        verifier.sentTo != null -> OtpStep(verifier, onBack = verifier::changeNumber)
        else -> PhoneStep(
            verifier = verifier,
            title = "Enter your mobile number",
            subtitle = "We'll send a 6-digit code by SMS to verify it's you. New here? This creates your account.",
            onBack = onBack,
            showBrand = true,
            extra = {
                OrDivider()
                SecondaryButton(
                    label = if (googleBusy) "Signing in…" else "Continue with Google",
                    onClick = onGoogle,
                    enabled = !googleBusy && !verifier.busy,
                    modifier = Modifier.fillMaxWidth(),
                    leading = { Icon(painterResource(R.drawable.ic_google_g), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp)) }
                )
                ErrorText(googleError)
            },
            footer = {
                TextButton(onClick = { mode = SignInMode.Email }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = SmartSpendTheme.colors.inkMuted)) { append("Signed up with email earlier? ") }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append("Use email") }
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "By continuing, you agree to Finzyy's Terms of Service and Privacy Policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartSpendTheme.colors.inkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

/**
 * Asks an account without a phone number (email or Google) to add one, so it can use phone
 * sign-in from now on. The number is verified with OTP and attached to the same account.
 */
@Composable
fun LinkPhoneScreen(
    onLinked: (UserData) -> Unit,
    onSkip: (() -> Unit)?,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val verifier = rememberPhoneVerifier { idToken ->
        val response = runCatching { RetrofitClient.apiService.linkPhone(FirebaseTokenPayload(idToken)) }
            .getOrElse { return@rememberPhoneVerifier "Can't reach Finzyy. Check your internet and try again." }
        val body = response.body()
        if (response.isSuccessful && body != null) {
            AuthSession.update(context, body)
            onLinked(body)
            null
        } else {
            serverMessage(response, fallback = "Couldn't add this number (${response.code()}).")
        }
    }

    if (verifier.sentTo != null) {
        OtpStep(verifier, onBack = verifier::changeNumber)
    } else {
        PhoneStep(
            verifier = verifier,
            title = "Add your mobile number",
            subtitle = "Finzyy now signs you in with your phone. Verify your number once and it's linked to this account and all its data.",
            onBack = onBack,
            showBrand = false,
            footer = {
                if (onSkip != null) {
                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                        Text("Skip for now", color = SmartSpendTheme.colors.inkMuted, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        )
    }
}

@Composable
private fun PhoneStep(
    verifier: PhoneVerifier,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    showBrand: Boolean,
    extra: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {}
) {
    val activity = LocalContext.current.findComponentActivity()
    val focus = remember { FocusRequester() }
    fun submit() {
        activity?.let { verifier.sendCode(it) }
    }

    AuthPage(onBack = onBack, footer = { footer() }) {
        if (showBrand) {
            Spacer(Modifier.height(8.dp))
            BrandMark()
        }
        AuthTitle(title, subtitle)

        OutlinedTextField(
            value = verifier.digits,
            onValueChange = verifier::onDigitsChange,
            label = { Text("Mobile number") },
            placeholder = { Text("98765 43210") },
            prefix = { Text("+91  ", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) },
            singleLine = true,
            isError = verifier.error != null,
            enabled = !verifier.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            shape = MaterialTheme.shapes.small
        )
        ErrorText(verifier.error)
        Spacer(Modifier.height(20.dp))
        LoadingButton(
            label = "Get OTP",
            onClick = ::submit,
            enabled = verifier.phoneValid,
            loading = verifier.busy
        )
        extra()
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun OtpStep(verifier: PhoneVerifier, onBack: () -> Unit) {
    val activity = LocalContext.current.findComponentActivity()
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)

    AuthPage(onBack = onBack) {
        AuthTitle(
            "Verify your number",
            "Enter the 6-digit code we sent to ${formatIndianNumber(verifier.sentTo.orEmpty())}."
        )
        OtpInput(
            code = verifier.code,
            onCodeChange = verifier::onCodeChange,
            isError = verifier.error != null,
            focusRequester = focus
        )
        ErrorText(verifier.error)
        Spacer(Modifier.height(24.dp))
        LoadingButton(
            label = "Verify and continue",
            onClick = verifier::submitCode,
            enabled = verifier.code.length == PhoneVerifier.OTP_LENGTH,
            loading = verifier.busy
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (verifier.resendIn > 0) {
                Text(
                    "Resend code in 0:%02d".format(verifier.resendIn),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartSpendTheme.colors.inkMuted
                )
            } else {
                Text(
                    "Resend code",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !verifier.busy) { activity?.let { verifier.sendCode(it, resend = true) } }
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Change number",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack)
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun EmailSignIn(onSignedIn: (AuthResponse) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    fun submit() {
        if (email.isBlank() || password.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val response = runCatching { RetrofitClient.apiService.login(email.trim(), password) }.getOrNull()
            val body = response?.body()
            when {
                response == null -> error = "Can't reach Finzyy. Check your internet and try again."
                response.isSuccessful && body != null -> {
                    AuthSession.save(context, body)
                    onSignedIn(body)
                }
                response.code() == 401 -> error = "Incorrect email or password."
                else -> error = serverMessage(response, "Sign-in failed (${response.code()}).")
            }
            busy = false
        }
    }

    AuthPage(onBack = onBack) {
        AuthTitle(
            "Sign in with email",
            "For accounts created before phone sign-in. After this, we'll ask you to add your mobile number so you can use OTP next time."
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim(); error = null },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        )
        ErrorText(error)
        Spacer(Modifier.height(24.dp))
        LoadingButton(
            label = "Sign in",
            onClick = ::submit,
            enabled = email.isNotBlank() && password.isNotBlank(),
            loading = busy
        )
    }
}

/** The backend's `detail` message when it sent one, else [fallback]. */
internal fun serverMessage(response: Response<*>, fallback: String): String {
    if (response.code() == 503) return "Phone sign-in isn't set up on the server yet. Please try again later."
    val detail = runCatching {
        JSONObject(response.errorBody()?.string().orEmpty()).optString("detail")
    }.getOrNull()
    return detail?.takeIf { it.isNotBlank() && !it.startsWith("[") } ?: fallback
}
