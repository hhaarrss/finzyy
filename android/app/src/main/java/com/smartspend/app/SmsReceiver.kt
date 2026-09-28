package com.smartspend.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.smartspend.app.notifications.TransactionNotifier
import com.smartspend.app.sms.BankSenderWhitelist
import com.smartspend.app.sms.SmsTransactionParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * BroadcastReceiver that intercepts incoming SMS messages from whitelisted bank senders,
 * parses them locally using SmsTransactionParser, and forwards only structured transaction fields to the backend.
 *
 * Raw SMS text is never transmitted or stored.
 *
 * The receiver never talks to the network itself: a broadcast only gets a few seconds of
 * execution, and when the app has been closed for a while the phone is usually in Doze
 * (no network) and the backend may be cold-starting. Instead, every parsed transaction is
 * written to a durable on-device queue and SmsSyncWorker (WorkManager) uploads it as soon as
 * the network is available — even if the app process is killed in between.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val now = System.currentTimeMillis()

        // Long bank SMS arrive as several PDUs in a single intent. Join the parts per sender so the
        // amount, account and UPI ref (which are often in different parts) are parsed together.
        val bySender = LinkedHashMap<String, Pair<StringBuilder, Long>>()
        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val part = sms.messageBody ?: continue
            val entry = bySender.getOrPut(sender) { StringBuilder() to sms.timestampMillis }
            entry.first.append(part)
        }

        var queuedAny = false
        for ((sender, entry) in bySender) {
            Log.d(TAG, "Received SMS from: $sender")

            if (!BankSenderWhitelist.isWhitelisted(sender)) {
                Log.d(TAG, "SMS ignored (sender not in bank whitelist): $sender")
                continue
            }

            val timestamp = stableSmsTimestamp(entry.second, now)
            val parsed = SmsTransactionParser.parse(entry.first.toString(), sender, timestamp)
            if (parsed != null) {
                Log.d(TAG, "Transactional SMS detected! Queuing structured payload for sync...")
                queueOfflineSms(context, parsed.toSmsPayload())
                queuedAny = true
            } else {
                Log.d(TAG, "SMS ignored because parsing did not produce a valid transaction")
            }
        }

        if (queuedAny) {
            SmsSyncWorker.enqueueNow(context)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private const val PREFS = "smart_spend_prefs"
        private const val QUEUE_KEY = "offline_sms_queue"

        /** Guards read-modify-write of the queue in SharedPreferences. */
        private val queueLock = Any()

        /** Only one flush may run at a time (worker + MainActivity.onResume can overlap). */
        private val flushMutex = Mutex()

        /**
         * Picks the timestamp used as the transaction date and in the backend fingerprint.
         *
         * The SMSC "sent" timestamp is identical whether we read the SMS from the live broadcast
         * (SmsMessage.timestampMillis) or later from the inbox (Telephony.Sms.DATE_SENT), so the
         * same SMS always produces the same fingerprint, while two different ₹1 payments never do.
         * Falls back to the device receive time if the SMSC clock is missing or clearly wrong.
         */
        fun stableSmsTimestamp(sentMillis: Long, receivedMillis: Long): Long =
            if (sentMillis > 0 && abs(sentMillis - receivedMillis) < 24 * 60 * 60 * 1000L) sentMillis
            else receivedMillis

        private fun dedupKey(obj: JSONObject): String {
            val ref = optNullableString(obj, "upi_ref")
            if (ref != null) return "upi:${ref.lowercase()}"
            return "fb:${obj.optDouble("amount")}:${optNullableString(obj, "account_last4")}:${obj.optString("date")}"
        }

        /**
         * Adds a parsed transaction to the durable queue. Returns false if the identical
         * transaction is already waiting in the queue.
         */
        fun queueOfflineSms(context: Context, payload: SmsPayload): Boolean {
            val sharedPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(queueLock) {
                try {
                    val queueArray = readQueue(sharedPrefs)
                    val item = JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("amount", payload.amount)
                        put("transaction_type", payload.transaction_type)
                        put("merchant_raw", payload.merchant_raw)
                        put("bank_sender_id", payload.bank_sender_id)
                        put("account_last4", payload.account_last4)
                        put("date", payload.date)
                        put("upi_ref", payload.upi_ref)
                        put("timestamp", System.currentTimeMillis())
                    }
                    val key = dedupKey(item)
                    for (i in 0 until queueArray.length()) {
                        if (dedupKey(queueArray.getJSONObject(i)) == key) {
                            Log.d(TAG, "Transaction already queued, skipping")
                            return false
                        }
                    }
                    queueArray.put(item)
                    writeQueue(sharedPrefs, queueArray.toString())
                    Log.d(TAG, "Queued structured SMS payload. Queue size: ${queueArray.length()}")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to queue SMS payload", e)
                    return false
                }
            }
        }

        fun hasQueuedSms(context: Context): Boolean {
            val sharedPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(queueLock) {
                return try {
                    readQueue(sharedPrefs).length() > 0
                } catch (e: Exception) {
                    false
                }
            }
        }

        /**
         * The queue holds parsed payments (amount, merchant, account digits, UPI ref) until they
         * sync, so it is stored encrypted like the login token. A queue written unencrypted by an
         * older build still reads; the next write stores it encrypted. Callers hold [queueLock].
         */
        private fun readQueue(prefs: android.content.SharedPreferences): JSONArray =
            JSONArray(LocalCrypto.decrypt(prefs.getString(QUEUE_KEY, null)) ?: "[]")

        /** commit(), not apply(): the receiver's process can die right after queuing. */
        private fun writeQueue(prefs: android.content.SharedPreferences, json: String) {
            prefs.edit().putString(QUEUE_KEY, LocalCrypto.encrypt(json)).commit()
        }

        private fun itemId(obj: JSONObject): String = obj.optString("id").ifEmpty { obj.toString() }

        /**
         * Uploads every queued transaction. Items that fail with a server/network error stay in
         * the queue; items added by the receiver while this flush is running are never lost.
         *
         * @return true if the queue is empty afterwards.
         */
        suspend fun flushOfflineQueue(context: Context, token: String, notify: Boolean = false): Boolean =
            flushMutex.withLock {
                val sharedPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val snapshot = synchronized(queueLock) {
                    try {
                        readQueue(sharedPrefs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Corrupt offline SMS queue, resetting", e)
                        writeQueue(sharedPrefs, "[]")
                        JSONArray()
                    }
                }
                if (snapshot.length() == 0) return@withLock true

                Log.d(TAG, "Flushing ${snapshot.length()} queued SMS...")
                val done = HashSet<String>()
                val service = RetrofitClient.apiService

                for (i in 0 until snapshot.length()) {
                    val obj = snapshot.getJSONObject(i)
                    val payload = try {
                        SmsPayload(
                            amount = obj.getDouble("amount"),
                            transaction_type = obj.getString("transaction_type"),
                            merchant_raw = optNullableString(obj, "merchant_raw"),
                            bank_sender_id = optNullableString(obj, "bank_sender_id"),
                            account_last4 = optNullableString(obj, "account_last4"),
                            date = obj.getString("date"),
                            upi_ref = optNullableString(obj, "upi_ref")
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Dropping malformed queued SMS", e)
                        done.add(itemId(obj))
                        continue
                    }

                    try {
                        val response = service.ingestSms("Bearer $token", payload)
                        val body = response.body()
                        if (response.isSuccessful && body != null && (body.success || body.message == "Duplicate transaction detected")) {
                            done.add(itemId(obj))
                            if (body.success) {
                                Log.d(TAG, "Synced SMS transaction ${body.transaction?.id}")
                                // Let any open screen reload so the new payment shows up immediately.
                                TransactionEvents.notifyChanged()
                                sharedPrefs.edit().apply {
                                    // Nothing reads it; older builds kept the last payment here in plain text.
                                    remove("last_sms")
                                    putInt("total_synced", sharedPrefs.getInt("total_synced", 0) + 1)
                                    commit()
                                }
                                if (notify) {
                                    TransactionNotifier.show(context, body.transaction, payload.amount, payload.merchant_raw, body.merchant_visit_count, body.month_spent)
                                }
                            } else {
                                Log.d(TAG, "SMS transaction was already on the server")
                            }
                        } else if (response.code() == 401) {
                            Log.w(TAG, "Received 401 Unauthorized — clearing stored token, keeping queue")
                            sharedPrefs.edit().remove("jwt_token").remove("user_email").commit()
                            break
                        } else if (response.code() in 400..499 && response.code() != 408 && response.code() != 429) {
                            // The server will never accept this payload; retrying forever would block the queue.
                            Log.w(TAG, "Backend rejected SMS permanently (${response.code()}), dropping")
                            done.add(itemId(obj))
                        } else {
                            Log.w(TAG, "Server error ${response.code()} / ${body?.message} — will retry")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Network error syncing SMS — will retry", e)
                    }
                }

                synchronized(queueLock) {
                    val current = try {
                        readQueue(sharedPrefs)
                    } catch (e: Exception) {
                        JSONArray()
                    }
                    val remaining = JSONArray()
                    for (i in 0 until current.length()) {
                        val obj = current.getJSONObject(i)
                        if (itemId(obj) !in done) remaining.put(obj)
                    }
                    writeQueue(sharedPrefs, remaining.toString())
                    remaining.length() == 0
                }
            }

        private fun optNullableString(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) {
                return null
            }
            return obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }
    }
}
