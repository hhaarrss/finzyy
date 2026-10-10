package com.smartspend.app.ui.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.smartspend.app.sms.HistoricalSmsSync
import com.smartspend.app.ui.theme.FinzyyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PRIVACY_POLICY_URL = "https://hhaarrss.github.io/finzyy/privacy-policy.html"
private const val PREFS_NAME = "smart_spend_prefs"
private const val PREF_SMS_PERMISSION_REQUESTED = "sms_permission_requested_once"

private sealed interface ConsentStep {
    data object Disclosure : ConsentStep
    data object Scanning : ConsentStep
    data object PermanentlyDenied : ConsentStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsConsentScreen(
    onBack: () -> Unit = {},
    onAutoSyncReady: () -> Unit = {},
    onManualEntry: () -> Unit = {}
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf<ConsentStep>(ConsentStep.Disclosure) }

    // The Settings deep link leaves the app, so the grant happens out of band. On return,
    // honour it instead of continuing to tell the user their permission is blocked.
    val permissionsGranted = rememberSmsPermissionsGranted()
    // Keyed only on the grant flip: keying on `step` too would cancel this effect the
    // moment it reassigns `step`, and the delayed navigation would never run.
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && step == ConsentStep.PermanentlyDenied) {
            step = ConsentStep.Scanning
            importInbox(context)
            onAutoSyncReady()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            step = ConsentStep.Scanning
            scope.launch {
                importInbox(context)
                onAutoSyncReady()
            }
        } else {
            val activity = context.findComponentActivity()
            val shouldShowRationale = activity != null && SMS_PERMISSIONS.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
            val requestedBefore = sharedPrefs.getBoolean(PREF_SMS_PERMISSION_REQUESTED, false)
            if (!shouldShowRationale && requestedBefore) {
                step = ConsentStep.PermanentlyDenied
            } else {
                sharedPrefs.edit().putBoolean(PREF_SMS_PERMISSION_REQUESTED, true).apply()
                onManualEntry()
            }
        }
    }

    fun handleContinue() {
        if (smsPermissionsGranted(context)) {
            step = ConsentStep.Scanning
            scope.launch {
                importInbox(context)
                onAutoSyncReady()
            }
        } else {
            sharedPrefs.edit().putBoolean(PREF_SMS_PERMISSION_REQUESTED, true).apply()
            permissionLauncher.launch(SMS_PERMISSIONS)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Auto-sync setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (step) {
                ConsentStep.Disclosure -> DisclosureContent(
                    onContinue = { handleContinue() }
                )
                ConsentStep.Scanning -> ScanningContent()
                ConsentStep.PermanentlyDenied -> PermanentlyDeniedContent(
                    onOpenSettings = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    onBackToHome = onManualEntry
                )
            }
        }
    }
}

@Composable
private fun DisclosureContent(onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "We use transaction SMS to automatically track your spending.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ConsentBullet("Detect transactions")
                ConsentBullet("Categorize expenses")
                ConsentBullet("Generate insights")
            }
        }

        Text(
            "We don't need your personal conversations or OTPs. Only messages from recognized bank senders are parsed, on your device. " +
                "Finzyy also checks your inbox in the background for bank SMS it missed while your phone was offline. " +
                "From each bank message, only the amount, merchant, date, bank and last 4 digits of the account are sent to our server " +
                "to categorize it and show your spending. The message text itself never leaves your phone.",
            color = FinzyyTheme.colors.inkMuted,
            style = MaterialTheme.typography.bodyMedium
        )

        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                context.startActivity(intent)
            }
        ) {
            Text("Read our Privacy Policy")
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun ConsentBullet(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ScanningContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Scanning your messages...", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Importing this month's bank SMS. Only amounts, merchants and dates leave your phone.",
            color = FinzyyTheme.colors.inkMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PermanentlyDeniedContent(onOpenSettings: () -> Unit, onBackToHome: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SMS permission is blocked", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "You've denied SMS access more than once, so Android won't show the prompt again. To enable auto-sync, allow SMS permission from your device Settings.",
                    color = FinzyyTheme.colors.inkMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app Settings")
                }
                OutlinedButton(onClick = onBackToHome, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue without auto-sync")
                }
            }
        }
    }
}

/**
 * The "Scanning your messages" step used to be a timed pause. It now imports the recent inbox
 * so the user lands on a Home that already has their transactions. Failures are swallowed —
 * live auto-sync still works, and the import can be re-run from Account.
 */
private suspend fun importInbox(context: Context) {
    val started = System.currentTimeMillis()
    runCatching { HistoricalSmsSync.run(context) }
    // Keep the step on screen long enough to read, even when the inbox is empty.
    val elapsed = System.currentTimeMillis() - started
    if (elapsed < 900) delay(900 - elapsed)
}
