package com.smartspend.app.notifications

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.smartspend.app.SessionStore
import com.smartspend.app.ui.notifications.CategorizeSheet
import com.smartspend.app.ui.notifications.fileTransaction
import com.smartspend.app.ui.theme.SmartSpendTheme
import com.smartspend.app.ui.theme.ThemePreference
import com.smartspend.app.ui.theme.isDark

/**
 * Opened by the notification's Categorize action. It has no window of its own — just the
 * category sheet over whatever the user was doing — and closes as soon as a category is saved.
 *
 * The sheet and the save are shared with the Notifications page (ui/notifications), so both
 * write the same correction: the transaction and the user's merchant mapping (Layer 1).
 */
class CategorizeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        ThemePreference.load(this)

        val txId = intent.getIntExtra(TransactionNotifier.EXTRA_TX_ID, -1)
        if (txId < 0) { finish(); return }
        val merchant = intent.getStringExtra(TransactionNotifier.EXTRA_MERCHANT)?.takeIf { it.isNotBlank() }
        val merchantRaw = intent.getStringExtra(TransactionNotifier.EXTRA_MERCHANT_RAW)?.takeIf { it.isNotBlank() }
        val current = intent.getStringExtra(TransactionNotifier.EXTRA_CATEGORY)
        val amount = intent.getDoubleExtra(TransactionNotifier.EXTRA_AMOUNT, 0.0)
        val credit = intent.getBooleanExtra(TransactionNotifier.EXTRA_CREDIT, false)

        setContent {
            SmartSpendTheme(darkTheme = ThemePreference.mode.isDark()) {
                CategorizeSheet(
                    amount = amount,
                    credit = credit,
                    merchant = merchant ?: merchantRaw ?: "this payment",
                    current = current,
                    onPick = { category ->
                        val ok = fileTransaction(txId, merchantRaw, merchant, category)
                        if (ok) {
                            TransactionNotifier.showFiled(this, txId, amount, credit, merchant ?: merchantRaw ?: "This merchant", category)
                            finish()
                        }
                        ok
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
