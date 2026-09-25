package com.smartspend.app.ui.notifications

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.ui.components.EmptyNote
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.Hairline
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.ScreenHeader
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.TransactionRow
import com.smartspend.app.ui.components.TransactionSheet
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.rememberTransactionsVersion
import com.smartspend.app.ui.components.shortDate
import com.smartspend.app.ui.components.transactionDays
import com.smartspend.app.ui.theme.LedgerAmount
import com.smartspend.app.ui.theme.SmartSpendTheme
import kotlin.math.roundToInt

/**
 * Two lists. NEEDS ATTENTION is things the user can act on right here: file a payment the
 * categoriser couldn't place (the same sheet and the same save as the notification's
 * Categorize action), open the Budget screen for an overspent category, or look at unusual
 * spending. RECENT ACTIVITY is the payments picked up from bank SMS, newest first, by day.
 *
 * Opening the page marks what is listed as read (local only); items new since the last visit
 * carry a dot for this visit.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit, onBudget: () -> Unit, onCategory: (String) -> Unit) {
    val context = LocalContext.current
    var bundle by remember { mutableStateOf<AttentionBundle?>(null) }
    var newKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val liveVersion = rememberTransactionsVersion()
    var categorizing by remember { mutableStateOf<TxView?>(null) }
    var openTx by remember { mutableStateOf<TxView?>(null) }

    LaunchedEffect(reloadKey, liveVersion) {
        error = null
        try {
            val loaded = AttentionRepository.load(context)
            // Work out what is new *before* marking everything read.
            newKeys = newKeys + AttentionRepository.unreadKeys(context, loaded)
            bundle = loaded
            AttentionRepository.markRead(context, loaded)
        } catch (e: Exception) {
            if (bundle == null) error = e.localizedMessage ?: "Can't reach SmartSpend right now."
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        val loaded = bundle
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { ScreenHeader(title = "Notifications", onBack = onBack) }

            when {
                loaded != null -> {
                    item { SectionLabel("Needs attention", loaded.attention.size) }
                    if (loaded.attention.isEmpty()) {
                        item {
                            EmptyNote(
                                title = "Nothing needs your attention",
                                body = "New transactions will show up here.",
                                modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp)
                            )
                        }
                    } else {
                        item { Hairline(Modifier.padding(horizontal = ScreenGutter)) }
                        loaded.attention.forEachIndexed { i, item ->
                            item(key = item.key) {
                                Column {
                                    if (i > 0) Hairline(Modifier.padding(start = ScreenGutter + 52.dp, end = ScreenGutter))
                                    AttentionRow(
                                        item = item,
                                        isNew = item.key in newKeys,
                                        onCategorize = { categorizing = it },
                                        onBudget = onBudget,
                                        onCategory = onCategory
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.size(20.dp)) }
                    item { SectionLabel("Recent activity", null) }
                    if (loaded.recent.isEmpty()) {
                        item {
                            EmptyNote(
                                title = "No payments detected yet",
                                body = "Payments picked up from your bank SMS will be listed here.",
                                modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp)
                            )
                        }
                    } else {
                        transactionDays(loaded.recent, onClick = { openTx = it }, keyPrefix = "recent")
                    }
                }
                error != null -> item { ErrorPanel(error.orEmpty(), onRetry = { reloadKey++ }) }
                else -> item { SkeletonBlocks(listOf(72.dp, 72.dp, 72.dp, 160.dp)) }
            }
        }
    }

    categorizing?.let { tx ->
        CategorizeSheet(
            amount = tx.amount,
            credit = tx.isCredit,
            merchant = tx.title,
            current = null,
            onPick = { category ->
                // For a payment the categoriser couldn't place, `merchant` is the text the bank sent.
                val ok = fileTransaction(tx.id, tx.merchant, tx.merchant, category)
                if (ok) {
                    categorizing = null
                    Toast.makeText(context, "Filed under $category", Toast.LENGTH_SHORT).show()
                }
                ok
            },
            onDismiss = { categorizing = null }
        )
    }

    openTx?.let { tx ->
        TransactionSheet(tx = tx, onDismiss = { openTx = null }, onChanged = { reloadKey++ })
    }
}

@Composable
private fun SectionLabel(text: String, count: Int?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Eyebrow(text, modifier = Modifier.weight(1f))
        if (count != null && count > 0) Eyebrow(count.toString(), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun AttentionRow(
    item: AttentionItem,
    isNew: Boolean,
    onCategorize: (TxView) -> Unit,
    onBudget: () -> Unit,
    onCategory: (String) -> Unit
) {
    when (item) {
        is NeedsCategoryItem -> Box {
            // The ordinary ledger row (same look as every list), with a dot when it's new.
            TransactionRow(
                tx = item.tx,
                onClick = { onCategorize(item.tx) },
                modifier = Modifier.padding(horizontal = ScreenGutter - 16.dp)
            )
            if (isNew) NewDot(Modifier.align(Alignment.CenterStart))
        }
        is OverBudgetItem -> SimpleRow(
            icon = Icons.Rounded.AccountBalanceWallet,
            title = "${item.category} is over budget",
            meta = "${money(item.spent)} OF ${money(item.limit)} · ${item.percent.roundToInt()}%",
            trailing = null,
            isNew = isNew,
            onClick = onBudget
        )
        is SpikeItem -> SimpleRow(
            icon = Icons.AutoMirrored.Rounded.TrendingUp,
            title = "Unusual ${item.category} spending",
            meta = "${money(item.amount)} · USUALLY ${money(item.average)}${shortDate(item.date).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()}",
            trailing = null,
            isNew = isNew,
            // The backend reports a spike per category (it doesn't say which payment), so this
            // opens that category's payments.
            onClick = { onCategory(item.category) }
        )
    }
}

@Composable
private fun SimpleRow(
    icon: ImageVector,
    title: String,
    meta: String,
    trailing: String?,
    isNew: Boolean,
    onClick: () -> Unit
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = ScreenGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .border(1.dp, SmartSpendTheme.colors.hairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = SmartSpendTheme.colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                Text(trailing, style = LedgerAmount, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (isNew) NewDot(Modifier.align(Alignment.CenterStart))
    }
}

@Composable
private fun NewDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = 7.dp)
            .size(6.dp)
            .background(SmartSpendTheme.colors.accent, CircleShape)
    )
}
