package com.smartspend.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartspend.app.sms.BankSenderWhitelist
import java.util.concurrent.TimeUnit

/**
 * Background sync for bank SMS that keeps working while the app is closed.
 *
 * Each run:
 *  1. Catch-up scan: reads bank SMS that arrived in the inbox since the last run and queues them.
 *     This recovers transactions whose SMS_RECEIVED broadcast was missed (process killed by the
 *     OEM battery manager, phone rebooted, receiver timed out, etc.).
 *  2. Uploads everything in the durable queue. Anything that fails is retried with backoff.
 *
 * Runs immediately (once the network is up) whenever SmsReceiver queues a transaction, and
 * periodically as a safety net. WorkManager persists both across process death and reboots.
 */
class SmsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)

        try {
            scanInboxForMissedSms(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Inbox catch-up scan failed", e)
        }

        val token = prefs.getString("jwt_token", null)
        if (token.isNullOrEmpty()) {
            // Not logged in: keep the queue, it will be flushed after login.
            Log.w(TAG, "No JWT token, deferring SMS sync")
            return Result.success()
        }

        val queueEmpty = SmsReceiver.flushOfflineQueue(ctx, token, notify = true)
        return if (queueEmpty || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
    }

    private fun scanInboxForMissedSms(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        val prefs = ctx.getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastScan = prefs.getLong(LAST_SCAN_KEY, 0L)
        if (lastScan == 0L) {
            // First run: start watching from now. Older SMS are imported with "Sync All Existing SMS".
            prefs.edit().putLong(LAST_SCAN_KEY, now).commit()
            return
        }

        // Small overlap so an SMS stored right at the previous boundary isn't skipped;
        // re-queued duplicates are dropped by the queue and by the backend fingerprint.
        val since = lastScan - SCAN_OVERLAP_MS
        var newest = lastScan
        var queued = 0

        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val sentIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)

            var scanned = 0
            while (c.moveToNext() && scanned < MAX_SCAN) {
                scanned++
                val received = c.getLong(dateIdx)
                if (received > newest) newest = received

                val sender = c.getString(addressIdx) ?: continue
                val body = c.getString(bodyIdx) ?: continue
                if (!BankSenderWhitelist.isWhitelisted(sender)) continue

                val ts = SmsReceiver.stableSmsTimestamp(c.getLong(sentIdx), received)
                val payload = SmsParser.parse(body, sender, ts) ?: continue
                if (SmsReceiver.queueOfflineSms(ctx, payload)) queued++
            }
        }

        prefs.edit().putLong(LAST_SCAN_KEY, maxOf(newest, lastScan)).commit()
        if (queued > 0) Log.d(TAG, "Catch-up scan queued $queued transaction(s)")
    }

    companion object {
        private const val TAG = "SmsSyncWorker"
        private const val LAST_SCAN_KEY = "last_inbox_scan_ts"
        private const val SCAN_OVERLAP_MS = 5 * 60 * 1000L
        private const val MAX_SCAN = 500
        private const val MAX_RETRIES = 10
        private const val WORK_NOW = "sms_sync_now"
        private const val WORK_PERIODIC = "sms_sync_periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Sync as soon as the network is available. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: an SMS arriving while a sync is running gets its own follow-up run.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Safety-net sync that catches missed broadcasts and retries failed uploads. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
