package com.smartspend.app.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartspend.app.ui.permission.notificationsAllowed
import com.smartspend.app.ui.permission.openAppNotificationSettings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.AuthSession
import com.smartspend.app.BuildConfig
import com.smartspend.app.RetrofitClient
import com.smartspend.app.UserData
import com.smartspend.app.ui.auth.formatIndianNumber
import com.smartspend.app.sms.HistoricalSmsSync
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SegmentedControl
import com.smartspend.app.ui.components.SmartSpendIcons
import com.smartspend.app.ui.permission.smsPermissionsGranted
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.ThemeMode
import com.smartspend.app.ui.theme.ThemePreference
import kotlinx.coroutines.launch

private const val PRIVACY_POLICY_URL = "https://hhaarrss.github.io/smart-spend/privacy-policy.html"
private const val TERMS_OF_SERVICE_URL = "https://hhaarrss.github.io/smart-spend/terms.html"
private const val SUPPORT_FAQ_URL = "https://hhaarrss.github.io/smart-spend/support.html"
// No blog exists yet; this opens the product site until one does. Swap the URL, nothing else.
private const val BLOG_URL = "https://hhaarrss.github.io/smart-spend/"
private const val SUPPORT_EMAIL = "smartspend4support@gmail.com"

private const val PREFS = "smart_spend_prefs"
const val PREF_NOTIFICATIONS_ENABLED = "pref_notifications_enabled"

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onEnableAutoSync: () -> Unit,
    onEditProfile: () -> Unit,
    onAddPhone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var profile by remember { mutableStateOf<UserData?>(null) }
    val cachedEmail = remember { prefs.getString("user_email", null) }
    val cachedName = remember { prefs.getString("user_name", null) }
    val cachedPhone = remember { prefs.getString("user_phone", null)?.takeIf { it.isNotBlank() } }
    val email = profile?.email ?: cachedEmail
    val name = profile?.full_name?.takeIf { it.isNotBlank() } ?: cachedName
    val phone = profile?.phone_number ?: cachedPhone
    val phoneVerified = phone != null

    var notifications by remember { mutableStateOf(prefs.getBoolean(PREF_NOTIFICATIONS_ENABLED, true)) }
    // Re-read on resume: the user may have just come back from system settings.
    var systemAllows by remember { mutableStateOf(notificationsAllowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemAllows = notificationsAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Turning the switch on while Android blocks the app would do nothing, so send the user to
    // the one place that can unblock it.
    fun setNotifications(on: Boolean) {
        notifications = on
        prefs.edit().putBoolean(PREF_NOTIFICATIONS_ENABLED, on).apply()
        if (on && !systemAllows) openAppNotificationSettings(context)
    }
    var syncing by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        profile = runCatching { RetrofitClient.apiService.getMyProfile().body() }.getOrNull()
        profile?.let { AuthSession.update(context, it) }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("No app can open this link")
        }
    }

    fun rateApp() {
        val pkg = context.packageName
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: ActivityNotFoundException) {
            open("https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    fun syncInbox() {
        if (!smsPermissionsGranted(context)) {
            onEnableAutoSync()
            return
        }
        syncing = true
        scope.launch {
            val result = runCatching { HistoricalSmsSync.run(context) }
            syncing = false
            result.onSuccess { toast(if (it.synced == 0) "Checked ${it.scanned} messages — nothing new" else "Added ${it.synced} transactions from SMS") }
                .onFailure { toast("Sync failed: ${it.localizedMessage}") }
        }
    }

    val displayName = name?.takeIf { it.isNotBlank() }
        ?: email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
        ?: "Your account"

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenHeader(title = "Account", onBack = onBack)

            // ── Profile ──────────────────────────────────────────────────
            Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(0.dp)) {
                Row(
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "Edit profile", onClick = onEditProfile)
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            displayName.first().uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (email != null) {
                            Text(email, style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("Edit profile", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Default.Phone,
                    title = phone?.let(::formatIndianNumber) ?: "Add phone number",
                    subtitle = when {
                        phone == null -> "Sign in with OTP from next time"
                        phoneVerified -> "Verified · used to sign in"
                        else -> "Not verified yet"
                    },
                    onClick = if (phone == null) onAddPhone else null,
                    chevron = phone == null,
                    trailing = {
                        if (phone != null && phoneVerified) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Verified", tint = SmartSpendTheme.colors.positive, modifier = Modifier.size(22.dp))
                        }
                    }
                )
            }

            // ── Preferences ──────────────────────────────────────────────
            Group("Preferences") {
                SettingRow(
                    icon = Icons.Default.Notifications,
                    title = "Insight notifications",
                    subtitle = if (notifications && !systemAllows) "Blocked by Android — tap to allow" else "Budget alerts and spending nudges",
                    onClick = {
                        if (notifications && !systemAllows) openAppNotificationSettings(context) else setNotifications(!notifications)
                    },
                    trailing = {
                        Switch(
                            checked = notifications && systemAllows,
                            onCheckedChange = { setNotifications(it) }
                        )
                    }
                )
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(SmartSpendIcons.Theme)
                        Spacer(Modifier.width(14.dp))
                        Text("Theme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(12.dp))
                    SegmentedControl(
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = ThemePreference.mode.ordinal,
                        onSelect = { ThemePreference.set(context, ThemeMode.entries[it]) }
                    )
                }
                RowDivider()
                SettingRow(
                    icon = Icons.Default.Refresh,
                    title = if (syncing) "Syncing SMS…" else "Sync existing SMS",
                    subtitle = "Import bank messages already in your inbox",
                    onClick = if (syncing) null else ({ syncInbox() })
                )
            }

            // ── Help ─────────────────────────────────────────────────────
            Group("Help & more") {
                SettingRow(SmartSpendIcons.Help, "Help & FAQ", onClick = { open(SUPPORT_FAQ_URL) }, chevron = true)
                RowDivider()
                SettingRow(Icons.Default.Email, "Contact support", subtitle = SUPPORT_EMAIL, onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL")).putExtra(Intent.EXTRA_SUBJECT, "SmartSpend support"))
                    } catch (_: ActivityNotFoundException) {
                        toast("No email app found")
                    }
                }, chevron = true)
                RowDivider()
                SettingRow(Icons.Default.Star, "Rate us", subtitle = "Tell others what you think", onClick = { rateApp() }, chevron = true)
                RowDivider()
                SettingRow(SmartSpendIcons.Article, "Blog", subtitle = "Money tips and product news", onClick = { open(BLOG_URL) }, chevron = true)
            }

            // ── Legal ────────────────────────────────────────────────────
            Group("Privacy & legal") {
                SettingRow(Icons.Default.Lock, "Privacy policy", onClick = { open(PRIVACY_POLICY_URL) }, chevron = true)
                RowDivider()
                SettingRow(Icons.Default.Info, "Terms of service", onClick = { open(TERMS_OF_SERVICE_URL) }, chevron = true)
                RowDivider()
                SettingRow(
                    Icons.Default.Delete,
                    "Delete account",
                    subtitle = "Erase your account and all its data",
                    tint = SmartSpendTheme.colors.negative,
                    onClick = { showDelete = true }
                )
            }

            Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(0.dp)) {
                SettingRow(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    "Log out",
                    tint = SmartSpendTheme.colors.negative,
                    onClick = { confirmLogout = true }
                )
            }

            Text(
                "SmartSpend ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\nSMS are read on your phone; only the amount, merchant and date are sent.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenGutter + 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = SmartSpendTheme.colors.inkMuted,
                textAlign = TextAlign.Center
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Auto-sync pauses until you sign in again. Your data stays in your account.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
        )
    }

    if (showDelete) {
        DeleteAccountDialog(
            jwtToken = prefs.getString("jwt_token", null),
            onDismiss = { showDelete = false },
            onDeleted = {
                showDelete = false
                Toast.makeText(context, "Your account has been deleted.", Toast.LENGTH_LONG).show()
                onLogout()
            }
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, modifier = Modifier.padding(start = ScreenGutter + 4.dp, top = 8.dp))
        Block(modifier = Modifier.padding(horizontal = ScreenGutter), padding = PaddingValues(0.dp)) { content() }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 66.dp), color = SmartSpendTheme.colors.hairline)

@Composable
private fun IconChip(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(icon, tint ?: MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint ?: MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SmartSpendTheme.colors.inkMuted)
            }
        }
        trailing()
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SmartSpendTheme.colors.inkMuted, modifier = Modifier.size(20.dp))
        }
    }
}
