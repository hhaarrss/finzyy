package com.smartspend.app.sms

import android.content.Context
import android.provider.Telephony
import com.smartspend.app.RetrofitClient
import com.smartspend.app.SmsParser
import com.smartspend.app.SmsReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * One-shot import of the bank SMS already sitting in the inbox, for the current calendar
 * month only. Parsing stays on-device; only the parsed payload is sent, same as
 * [com.smartspend.app.SmsReceiver]. The backend's fingerprint dedup makes re-running it
 * harmless.
 *
 * Why this month: the app's screens are month-by-month, so older messages would add
 * transactions nobody is looking at, take far longer, and push months of spending onto a
 * server the user may not expect. Keeping it to the open month also makes the button
 * honest — it says what it will do before it does it.
 */
object HistoricalSmsSync {
    /** A month of SMS on a busy phone; a guard against pathological inboxes, not a limit in practice. */
    private const val SCAN_LIMIT = 1000

    data class Result(val scanned: Int, val synced: Int, val monthLabel: String)

    /** What a run would cover, for the button's label. */
    data class Scope(val bankMessages: Int, val monthLabel: String)

    private fun monthStartMillis(): Long =
        LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun monthLabel(): String =
        LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun <T> readMonth(context: Context, block: (String, String, Long) -> T?): List<T> {
        val results = mutableListOf<T>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(monthStartMillis().toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val sentIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            var seen = 0
            while (cursor.moveToNext() && seen < SCAN_LIMIT) {
                seen++
                val sender = cursor.getString(addressIdx) ?: continue
                val body = cursor.getString(bodyIdx) ?: continue
                val timestamp = SmsReceiver.stableSmsTimestamp(cursor.getLong(sentIdx), cursor.getLong(dateIdx))
                block(sender, body, timestamp)?.let(results::add)
            }
        }
        return results
    }

    /**
     * How many messages from known bank senders this month's import would look at. Requires
     * READ_SMS; callers check the grant first.
     */
    suspend fun scope(context: Context): Scope = withContext(Dispatchers.IO) {
        val senders = readMonth(context) { sender, _, _ ->
            sender.takeIf { BankSenderWhitelist.isWhitelisted(it) }
        }
        Scope(bankMessages = senders.size, monthLabel = monthLabel())
    }

    /** Requires READ_SMS; callers check the grant first. */
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        // Parse first, then upload: the cursor is closed before any network call, so a slow
        // or sleeping server never holds the SMS provider open.
        val payloads = readMonth(context) { sender, body, timestamp ->
            // Use the SMS's own timestamp (not "now") so each transaction keeps its real date and
            // matches the fingerprint of the same SMS already synced by SmsReceiver/SmsSyncWorker.
            SmsParser.parse(body, sender, timestamp)
        }

        var synced = 0
        for (payload in payloads) {
            // The auth interceptor supplies the signed-in user's token.
            val response = runCatching { RetrofitClient.apiService.ingestSms("", payload) }.getOrNull()
            if (response?.isSuccessful == true && response.body()?.success == true) synced++
        }
        Result(scanned = payloads.size, synced = synced, monthLabel = monthLabel())
    }
}
