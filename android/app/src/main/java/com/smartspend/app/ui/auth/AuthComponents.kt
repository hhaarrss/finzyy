package com.smartspend.app.ui.auth

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.smartspend.app.ui.components.BrandLoader
import com.smartspend.app.ui.components.RoundIconButton
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────── Phone verification state ───────────────────────────────

/**
 * Drives the phone → OTP steps shared by sign-in and "add your phone number".
 *
 * [onIdToken] receives the Firebase ID token once the number is verified and does the backend
 * call; it returns an error message to show, or null when it succeeded (the caller navigates on).
 */
internal class PhoneVerifier(
    private val scope: CoroutineScope,
    private val onIdToken: suspend (String) -> String?
) {
    var digits by mutableStateOf("")
    var code by mutableStateOf("")
    /** Set once the OTP has been sent — the UI shows the code step while this is non-null. */
    var sentTo by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var resendIn by mutableIntStateOf(0)
        private set

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var verifyJob: Job? = null
    private var timerJob: Job? = null
    private var finishing = false

    val e164: String get() = "+91$digits"

    /** Indian mobile numbers: 10 digits starting 6–9. */
    val phoneValid: Boolean get() = digits.length == 10 && digits.first() in '6'..'9'

    fun onDigitsChange(value: String) {
        // Accept pasted numbers like "+91 98765-43210": keep the last 10 digits.
        digits = value.filter(Char::isDigit).let { if (it.length > 10 && it.startsWith("91")) it.drop(2) else it }.take(10)
        error = null
    }

    fun onCodeChange(value: String) {
        code = value.filter(Char::isDigit).take(OTP_LENGTH)
        error = null
        if (code.length == OTP_LENGTH) submitCode()
    }

    fun sendCode(activity: Activity, resend: Boolean = false) {
        if (!phoneValid) {
            error = "Enter a valid 10-digit mobile number"
            return
        }
        if (busy) return
        error = null
        busy = true
        verifyJob?.cancel()
        verifyJob = scope.launch {
            PhoneAuthController.verify(activity, e164, if (resend) resendToken else null).collect { event ->
                when (event) {
                    is PhoneVerificationEvent.CodeSent -> {
                        verificationId = event.verificationId
                        resendToken = event.resendToken
                        sentTo = e164
                        code = ""
                        busy = false
                        startResendTimer()
                    }
                    is PhoneVerificationEvent.AutoVerified -> {
                        // Android read the SMS itself: show the code, then continue.
                        event.credential.smsCode?.let { code = it }
                        finish(event.credential)
                    }
                    is PhoneVerificationEvent.Failed -> {
                        busy = false
                        error = event.message
                    }
                }
            }
        }
    }

    fun submitCode() {
        val id = verificationId ?: return
        if (code.length != OTP_LENGTH || busy) return
        scope.launch { finish(PhoneAuthController.credentialFor(id, code)) }
    }

    fun changeNumber() {
        verifyJob?.cancel()
        timerJob?.cancel()
        sentTo = null
        code = ""
        error = null
        busy = false
    }

    private suspend fun finish(credential: PhoneAuthCredential) {
        if (finishing) return
        finishing = true
        busy = true
        error = null
        try {
            val signedIn = PhoneAuthController.signIn(credential)
            val idToken = signedIn.getOrNull()
            error = if (idToken != null) onIdToken(idToken) else signedIn.exceptionOrNull()?.message
        } finally {
            busy = false
            finishing = false
        }
    }

    private fun startResendTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            resendIn = RESEND_SECONDS
            while (resendIn > 0) {
                delay(1000)
                resendIn--
            }
        }
    }

    companion object {
        const val OTP_LENGTH = 6
        private const val RESEND_SECONDS = 30
    }
}

@Composable
internal fun rememberPhoneVerifier(onIdToken: suspend (String) -> String?): PhoneVerifier {
    val scope = rememberCoroutineScope()
    return remember { PhoneVerifier(scope, onIdToken) }
}

/** "98765 43210" */
internal fun formatIndianNumber(e164: String): String {
    val local = e164.removePrefix("+91")
    return if (local.length == 10) "+91 ${local.take(5)} ${local.drop(5)}" else e164
}

// ─────────────────────────────── Layout pieces ───────────────────────────────

/** Full-screen, scrollable, keyboard-aware page used by every sign-in step. */
@Composable
internal fun AuthPage(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(Modifier.padding(start = 12.dp, top = 8.dp).height(44.dp)) {
            if (onBack != null) {
                RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            content = content
        )
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp), content = footer)
    }
}

@Composable
internal fun AuthTitle(title: String, subtitle: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(10.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = SmartSpendTheme.colors.inkMuted)
    Spacer(Modifier.height(32.dp))
}

/** Brand mark shown above the first sign-in step. */
@Composable
internal fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("S", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text("Finzyy", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Primary action with an inline spinner while [loading]. */
@Composable
internal fun LoadingButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun ErrorText(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun OrDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(SmartSpendTheme.colors.hairline))
        Text(
            "or",
            modifier = Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = SmartSpendTheme.colors.inkMuted
        )
        Box(Modifier.weight(1f).height(1.dp).background(SmartSpendTheme.colors.hairline))
    }
}

/** Six boxes backed by a single hidden text field, so paste and SMS autofill work. */
@Composable
internal fun OtpInput(
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = TextFieldValue(code, selection = TextRange(code.length)),
        onValueChange = { onCodeChange(it.text) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = "One-time code" },
        decorationBox = {
            // Min intrinsic height keeps the six boxes one height even if a large font makes some grow.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(PhoneVerifier.OTP_LENGTH) { i ->
                    val char = code.getOrNull(i)
                    val active = i == code.length
                    val borderColor = when {
                        isError -> MaterialTheme.colorScheme.error
                        active -> MaterialTheme.colorScheme.primary
                        char != null -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        else -> SmartSpendTheme.colors.hairline
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 58.dp)
                            .border(BorderStroke(if (active) 2.dp else 1.dp, borderColor), RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            char?.toString().orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    )
}

/** Small circular avatar with the user's initials. */
@Composable
internal fun InitialsAvatar(name: String, size: Int = 72) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(
        Modifier
            .size(size.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Shown while the app decides where a signed-in user goes. */
@Composable
fun LoadingPage() {
    BrandLoader()
}
