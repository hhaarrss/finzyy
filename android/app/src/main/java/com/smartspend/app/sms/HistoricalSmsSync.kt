package com.smartspend.app.sms

import android.content.Context
import android.provider.Telephony
import com.smartspend.app.RetrofitClient
import com.smartspend.app.SmsParser
import com.smartspend.app.SmsReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot import of bank SMS already sitting in the inbox — what the old dashboard's
 * "Sync all existing SMS" button did, moved here so the consent flow and the Account page
 * share one implementation. Parsing stays on-device; only the parsed payload is sent,
 * same as [com.smartspend.app.SmsReceiver]. The backend's fingerprint dedup makes re-running
 * it harmless.
 */
object HistoricalSmsSync {
    private const val SCAN_LIMIT = 100

    data class Result(val scanned: Int, val synced: Int)

    /** Requires READ_SMS; callers check the grant first. */
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            null, null, Telephony.Sms.DATE + " DESC"
        )

        var scanned = 0
        var synced = 0
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            val sentIdx = it.getColumnIndex(Telephony.Sms.DATE_SENT)
            while (it.moveToNext() && scanned < SCAN_LIMIT) {
                scanned++
                val sender = it.getString(addressIdx) ?: ""
                val body = it.getString(bodyIdx) ?: ""
                // Use the SMS's own timestamp (not "now") so each transaction keeps its real date and
                // matches the fingerprint of the same SMS already synced by SmsReceiver/SmsSyncWorker.
                val ts = SmsReceiver.stableSmsTimestamp(it.getLong(sentIdx), it.getLong(dateIdx))
                val payload = SmsParser.parse(body, sender, ts) ?: continue
                // The auth interceptor supplies the signed-in user's token.
                val response = runCatching { RetrofitClient.apiService.ingestSms("", payload) }.getOrNull()
                if (response?.isSuccessful == true && response.body()?.success == true) synced++
            }
        }
        Result(scanned, synced)
    }
}
