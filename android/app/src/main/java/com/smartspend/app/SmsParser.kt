package com.smartspend.app

import com.smartspend.app.sms.SmsTransactionParser

/**
 * Legacy SmsParser facade that delegates directly to SmsTransactionParser.
 * Ensures that manual test paste and historical SMS inbox sync in MainActivity
 * also benefit from the full on-device regex parsing rules.
 */
object SmsParser {
    fun parse(rawSms: String, sender: String, timestampMillis: Long = System.currentTimeMillis()): SmsPayload? {
        return SmsTransactionParser.parse(rawSms, sender, timestampMillis)?.toSmsPayload()
    }
}
