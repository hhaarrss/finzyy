package com.smartspend.app.ui.notifications

import android.content.Context
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.components.SpendData
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.isFromSms
import com.smartspend.app.ui.components.needsReview
import com.smartspend.app.ui.components.toView
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

/** One thing on the NEEDS ATTENTION list. [key] identifies it across reloads for read state. */
sealed interface AttentionItem {
    val key: String
}

/** A payment the categoriser couldn't place. */
data class NeedsCategoryItem(val tx: TxView) : AttentionItem {
    override val key get() = "tx:${tx.id}"
}

data class OverBudgetItem(
    val category: String,
    val spent: Double,
    val limit: Double,
    val percent: Double,
    val month: String
) : AttentionItem {
    override val key get() = "budget:$category:$month"
}

/** Unusually large spending in a category. The backend reports it per category, not per payment. */
data class SpikeItem(val category: String, val amount: Double, val average: Double, val date: String?) : AttentionItem {
    override val key get() = "spike:$category:${date?.take(7).orEmpty()}"
}

data class AttentionBundle(
    /** Needs-a-category payments first, then budgets over limit, then spikes. */
    val attention: List<AttentionItem>,
    /** Payments picked up from bank SMS, newest first. */
    val recent: List<TxView>
) {
    val needsCategory: List<NeedsCategoryItem> get() = attention.filterIsInstance<NeedsCategoryItem>()
    val budgets: List<OverBudgetItem> get() = attention.filterIsInstance<OverBudgetItem>()
    val spikes: List<SpikeItem> get() = attention.filterIsInstance<SpikeItem>()
}

/**
 * Loads what the Notifications page shows, entirely from existing endpoints:
 *   GET /transactions/needs-review  - payments needing a category
 *   GET /insights/summary           - budget alerts and anomalies
 *   GET /transactions/              - recent payments
 * and keeps the unread count the Home bell shows.
 *
 * "Unread" is local: the set of attention items the user has already seen on the page. The
 * count covers NEEDS ATTENTION only, never the Recent activity history.
 */
object AttentionRepository {
    private const val PREFS = "smart_spend_prefs"
    private const val KEY_SEEN = "notif_seen_keys"

    private val _unread = MutableStateFlow(0)

    /** Attention items not yet seen on the Notifications page (the bell badge). */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    suspend fun load(context: Context): AttentionBundle {
        val bundle = fetch()
        _unread.value = unreadKeys(context, bundle).size
        return bundle
    }

    /** Keys of attention items the user hasn't opened the page on yet. */
    fun unreadKeys(context: Context, bundle: AttentionBundle): Set<String> =
        bundle.attention.map { it.key }.toSet() - seen(context)

    /** Everything currently listed counts as seen; items that have since resolved are forgotten. */
    fun markRead(context: Context, bundle: AttentionBundle) {
        prefs(context).edit().putStringSet(KEY_SEEN, bundle.attention.map { it.key }.toSet()).apply()
        _unread.value = 0
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun seen(context: Context): Set<String> = prefs(context).getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    private suspend fun fetch(): AttentionBundle = coroutineScope {
        val today = LocalDate.now()
        val month = YearMonth.now().toString()

        val review = async { RetrofitClient.apiService.getNeedsReviewTransactions("") }
        val insights = async { runCatching { RetrofitClient.apiService.getInsightsSummaryNoAuth() }.getOrNull() }
        val recent = async {
            runCatching { SpendData.transactions(today.minusDays(30), today, maxPages = 1) }.getOrDefault(emptyList())
        }

        val reviewResponse = review.await()
        val reviewBody = reviewResponse.body()
        if (!reviewResponse.isSuccessful || reviewBody == null) {
            throw IOException("Notifications failed to load (${reviewResponse.code()})")
        }
        // The endpoint also returns anything filed under "Other"; only payments the app itself
        // marks as unsorted count, so a payment you filed under Other doesn't nag forever.
        val needs = reviewBody.transactions.filter { it.needsReview }.map { NeedsCategoryItem(it.toView()) }

        val summary = insights.await()?.takeIf { it.isSuccessful }?.body()
        val budgets = summary?.budget_alerts.orEmpty().mapNotNull { alert ->
            val percent = alert.number("percent") ?: return@mapNotNull null
            if (percent <= 100.0) return@mapNotNull null // "over limit", not merely close
            OverBudgetItem(
                category = alert["category"] as? String ?: return@mapNotNull null,
                spent = alert.number("spent") ?: 0.0,
                limit = alert.number("limit") ?: 0.0,
                percent = percent,
                month = month
            )
        }
        // Budget breaches also come back as "budget" anomalies; they are the same thing as the
        // budgets above, so only spending spikes are listed here.
        val spikes = summary?.anomalies.orEmpty().mapNotNull { anomaly ->
            if (anomaly["kind"] != "spike") return@mapNotNull null
            SpikeItem(
                category = anomaly["category"] as? String ?: return@mapNotNull null,
                amount = anomaly.number("amount") ?: 0.0,
                average = anomaly.number("avg") ?: 0.0,
                date = anomaly["date"] as? String
            )
        }

        val detected = recent.await()
            .filter { it.isFromSms }
            .sortedByDescending { it.date }
            .take(40)
            .map { it.toView() }

        AttentionBundle(attention = needs + budgets + spikes, recent = detected)
    }
}

private fun Map<String, Any>.number(key: String): Double? = (this[key] as? Number)?.toDouble()
