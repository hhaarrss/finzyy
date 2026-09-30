package com.smartspend.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.smartspend.app.MainActivity
import com.smartspend.app.R
import com.smartspend.app.TransactionData
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.theme.Categories
import com.smartspend.app.ui.theme.CategoryIcon
import java.time.LocalDate

/**
 * The alert posted after a bank SMS has synced. Built only from the parsed fields and the
 * backend's reply — the SMS text itself never reaches this code.
 *
 * One notification per transaction (id = transaction id, so the Categorize sheet can update it
 * in place), grouped per day under a summary so five payments collapse into one entry.
 */
object TransactionNotifier {
    private const val TAG = "TransactionNotifier"

    /** Own channel so transaction alerts can be muted in system settings without muting budget pushes. */
    const val CHANNEL_ID = "transaction_alerts"
    private const val LEGACY_CHANNEL_ID = "smartspend_sms_sync"

    const val EXTRA_TX_ID = "tx_id"
    const val EXTRA_MERCHANT_RAW = "merchant_raw"
    const val EXTRA_MERCHANT = "merchant"
    const val EXTRA_CATEGORY = "category"
    const val EXTRA_AMOUNT = "amount"
    const val EXTRA_CREDIT = "credit"

    private const val INK = 0xFF1C1B19.toInt()
    private const val PAPER = 0xFFF4F1EA.toInt()
    private const val GOLD = 0xFFB8965A.toInt()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transaction alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A note each time a bank SMS payment is added, with a quick way to categorise it"
            }
        )
        // Replaced by the channel above; leaving it would show a dead toggle in system settings.
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    /**
     * @param merchantRaw the merchant as parsed on the phone — the Layer 1 mapping key, same
     *   field the backend already received in the ingest payload.
     */
    fun show(
        context: Context,
        tx: TransactionData?,
        fallbackAmount: Double,
        merchantRaw: String?,
        visitCount: Int? = null,
        monthSpent: Double? = null
    ) {
        try {
            ensureChannel(context)
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return

            val amount = tx?.amount ?: fallbackAmount
            val credit = tx?.type.equals("credit", ignoreCase = true)
            val merchant = tx?.merchant?.takeIf { it.isNotBlank() } ?: merchantRaw ?: "Payment"
            val needsReview = tx?.review_status == "needs_review" ||
                Categories.canonical(tx?.category) == Categories.NEEDS_REVIEW
            val category = tx?.category?.takeUnless { needsReview }

            val amountText = (if (credit) "+" else "") + money(amount)
            val title = if (needsReview) "$amountText · $merchant — needs a category" else "$amountText · $merchant"
            val visit = visitCount?.takeIf { !credit && it > 0 }?.let {
                if (it == 1) "your first visit here" else "your ${ordinal(it)} visit here"
            }
            val text = when {
                needsReview -> "Tap Categorize — we'll remember it for next time"
                credit -> "$category · received"
                else -> listOfNotNull(category ?: "Synced from your bank SMS", visit).joinToString(" · ")
            }
            // Both extras come with the sync response, so there's no extra request; an older
            // server simply leaves them out.
            val expanded = monthSpent?.let { "$text\n${money(it)} spent this month" } ?: text
            val id = tx?.id ?: (System.currentTimeMillis() and 0x7fffffff).toInt()
            val group = groupKey()

            val builder = baseBuilder(context, group)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                .setLargeIcon(iconBitmap(context, if (needsReview) Icons.Rounded.QuestionMark else CategoryIcon(category), needsReview))

            if (tx != null) {
                builder.addAction(
                    0, if (needsReview) "Categorize" else "Change category",
                    categorizeIntent(context, tx, merchantRaw, id, credit)
                )
            }
            nm.notify(id, builder.build())
            postSummary(context, group)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show transaction notification", e)
        }
    }

    /** Swaps the alert for a quiet confirmation once the user has picked a category. */
    fun showFiled(context: Context, id: Int, amount: Double, credit: Boolean, merchant: String, category: String) {
        try {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val group = groupKey()
            val n = baseBuilder(context, group)
                .setContentTitle((if (credit) "+" else "") + money(amount) + " · " + merchant)
                .setContentText("Filed under $category · $merchant will go there next time")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Filed under $category · $merchant will go there next time"))
                .setLargeIcon(iconBitmap(context, CategoryIcon(category), false))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setTimeoutAfter(6_000)
                .build()
            nm.notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    private fun baseBuilder(context: Context, group: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rupee)
            .setColor(GOLD)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setGroup(group)

    /** Collapses the day's alerts into one stack; Android shows the children when it's expanded. */
    private fun postSummary(context: Context, group: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val children = nm.activeNotifications.filter {
            it.notification.group == group && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
        }
        if (children.size < 2) return
        val inbox = NotificationCompat.InboxStyle()
        children.take(6).forEach { sbn ->
            sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.let { inbox.addLine(it) }
        }
        val summary = "${children.size} payments added today"
        inbox.setSummaryText(summary)
        val n = baseBuilder(context, group)
            .setContentTitle(summary)
            .setContentText("Tap to review them in SmartSpend")
            .setStyle(inbox)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(group.hashCode(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    /** 2 → "2nd", 3 → "3rd", 11 → "11th", 22 → "22nd". */
    internal fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    private fun groupKey() = "transactions_${LocalDate.now()}"

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun categorizeIntent(context: Context, tx: TransactionData, merchantRaw: String?, id: Int, credit: Boolean): PendingIntent {
        val intent = Intent(context, CategorizeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TX_ID, tx.id)
            .putExtra(EXTRA_MERCHANT_RAW, merchantRaw)
            .putExtra(EXTRA_MERCHANT, tx.merchant)
            .putExtra(EXTRA_CATEGORY, tx.category)
            .putExtra(EXTRA_AMOUNT, tx.amount)
            .putExtra(EXTRA_CREDIT, credit)
        // Request code = notification id, so each alert's action carries its own transaction.
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Draws a category icon from the app's own map ([CategoryIcon]) onto a disc, so the
     * notification uses exactly the icons the app does instead of a second set of drawables.
     */
    private fun iconBitmap(context: Context, icon: ImageVector, attention: Boolean): Bitmap {
        val px = (48 * context.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (attention) GOLD else INK }
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, disc)

        val glyph = px * 0.5f
        val scale = glyph / icon.viewportWidth
        canvas.save()
        canvas.translate((px - glyph) / 2f, (px - glyph) / 2f)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (attention) INK else PAPER; style = Paint.Style.FILL }
        drawGroup(canvas, icon.root, paint)
        canvas.restore()
        return bmp
    }

    private fun drawGroup(canvas: Canvas, group: VectorGroup, paint: Paint) {
        for (node in group) {
            when (node) {
                is VectorPath -> canvas.drawPath(node.pathData.toPath().asAndroidPath(), paint)
                is VectorGroup -> drawGroup(canvas, node, paint)
            }
        }
    }
}
