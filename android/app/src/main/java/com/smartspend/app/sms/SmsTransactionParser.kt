package com.smartspend.app.sms

import com.smartspend.app.SmsPayload
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * Structured transaction details parsed on-device from SMS alerts.
 * Raw SMS text is never stored or transmitted.
 */
data class ParsedSmsTransaction(
    val amount: Double,
    val transactionType: String, // "debit" or "credit"
    val merchantRaw: String,
    val bankSenderId: String,
    val accountLast4: String,
    val date: String,            // ISO-8601 UTC string
    val upiRef: String?,
    val bank: String
) {
    fun toSmsPayload(): SmsPayload = SmsPayload(
        amount = amount,
        transaction_type = transactionType,
        merchant_raw = merchantRaw,
        bank_sender_id = bankSenderId,
        account_last4 = accountLast4,
        date = date,
        upi_ref = upiRef
    )
}

/**
 * On-device SMS transaction parser ported directly from backend/utils/sms_parser.py.
 * Replicates Python regex patterns, spam detection, debit/credit classification,
 * amount/account/date/merchant extraction, and bank identification.
 */
object SmsTransactionParser {

    // 0. Reject marketing, promotional, OTP, loan offers, and mandate requests
    // Exact port of spam_patterns in backend/utils/sms_parser.py lines 106-134
    private val SPAM_PATTERNS = listOf(
        Regex("""save\s+(?:rs\.?|inr|₹)""", RegexOption.IGNORE_CASE),
        Regex("""earn\s+up\s+to""", RegexOption.IGNORE_CASE),
        Regex("""cashback\s+every""", RegexOption.IGNORE_CASE),
        Regex("""apply\s+now""", RegexOption.IGNORE_CASE),
        Regex("""pre-approved""", RegexOption.IGNORE_CASE),
        Regex("""pre\s+approved""", RegexOption.IGNORE_CASE),
        Regex("""loan\s+offer""", RegexOption.IGNORE_CASE),
        Regex("""get\s+(?:flat|up\s+to)\s+(?:rs\.?|inr|₹|\d+%)""", RegexOption.IGNORE_CASE),
        Regex("""win\s+up\s+to""", RegexOption.IGNORE_CASE),
        Regex("""lifetime\s+free""", RegexOption.IGNORE_CASE),
        Regex("""at\s+no\s+extra\s+charge""", RegexOption.IGNORE_CASE),
        Regex("""play\s+\d+\+\s+games""", RegexOption.IGNORE_CASE),
        Regex("""pro\s+pass""", RegexOption.IGNORE_CASE),
        Regex("""voucher""", RegexOption.IGNORE_CASE),
        Regex("""coupon\s+code""", RegexOption.IGNORE_CASE),
        Regex("""promo\s+code""", RegexOption.IGNORE_CASE),
        Regex("""discount\s+on""", RegexOption.IGNORE_CASE),
        Regex("""mandate\s+collect\s+request""", RegexOption.IGNORE_CASE),
        Regex("""request\s+for\s+blocking\s+of\s+funds""", RegexOption.IGNORE_CASE),
        Regex("""otp\s+is""", RegexOption.IGNORE_CASE),
        Regex("""verification\s+code""", RegexOption.IGNORE_CASE),
        Regex("""do\s+not\s+share\s+(?:this\s+)?otp""", RegexOption.IGNORE_CASE),
        Regex("""claim\s+now""", RegexOption.IGNORE_CASE),
        Regex("""offer\s+ends""", RegexOption.IGNORE_CASE),
        Regex("""congratulations""", RegexOption.IGNORE_CASE),
        Regex("""credit\s+card\s+limit""", RegexOption.IGNORE_CASE),
        Regex("""personal\s+loan""", RegexOption.IGNORE_CASE)
    )

    // Debit and credit keyword lists — comprehensive Indian bank SMS patterns
    private val DEBIT_KEYWORDS = listOf(
        "debited", "debitted", "dr.", "dr ",
        "spent", "paid", "withdrawn", "payment of", "charge",
        "withdrew", "txn to", "used for", "used at", "transaction of", "sent to", "transfer to",
        "auto debit", "auto-debit", "emi deducted", "emi paid", "mandate executed",
        "purchase of", "purchase at", "pos txn"
    )

    private val CREDIT_KEYWORDS = listOf(
        "credited", "creditted", "cr.", "cr ",
        "deposited", "received from", "received rs",
        "credited with", "refund of", "money received", "salary credited"
    )

    // Amount extraction regex from sms_parser.py line 158
    private val AMOUNT_PATTERN = Regex(
        """(?:rs\.?|inr|₹)\s*([\d,]+\.?\d*)|([\d,]+\.?\d*)\s*(?:rs\.?|inr|₹)""",
        RegexOption.IGNORE_CASE
    )

    // Account last 4 extraction — covers xx1234, ****1234, ending 1234, a/c 1234, card 1234
    private val ACCT_PATTERN = Regex(
        """(?:a/c|acct|ac|account|card|ending|no\.|linked)\s*(?:no\.?\s*)?[xX*#]{0,10}(\d{3,4})(?!\d)""",
        RegexOption.IGNORE_CASE
    )

    // Fallback: bare xx followed by digits (e.g. "xx1234", "****1234")
    private val ACCT_PATTERN_FALLBACK = Regex(
        """(?:xx+|\*{2,})(\d{4})""",
        RegexOption.IGNORE_CASE
    )

    // Merchant / Payee extraction regexes from sms_parser.py lines 181-186
    private val MERCH_PATTERNS = listOf(
        Regex(""";?\s*([A-Za-z0-9\s._&\-]+?)\s+credited""", RegexOption.IGNORE_CASE),
        Regex("""(?:info:|narration:|remarks?:|towards)\s+([A-Za-z0-9\s._&\-]+?)(?:\s+on\b|\s+ref\b|\s+upi\b|\s+val\b|\.|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:vpa)\s+([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:from|to|at)\s+([A-Za-z0-9\s._&\-]+?)(?:\s+on\b|\s+ref\b|\s+upi\b|\s+val\b|\.|$)""", RegexOption.IGNORE_CASE)
    )

    // Prefix cleanup pattern from sms_parser.py line 192
    private val MERCH_PREFIX_CLEANUP = Regex(
        """^(?:a\s+transaction\s+of|payment\s+of|txn\s+of)\s+""",
        RegexOption.IGNORE_CASE
    )

    // UPI / IMPS / RRN / transaction reference number extraction
    // Catches: UPI Ref 123456789012, IMPS Ref No 123456, RRN: 123456, Ref:ABC123
    private val UPI_REF_PATTERN = Regex(
        """(?i)(?:upi\s*(?:ref(?:erence)?\s*(?:no\.?|num\.?|number)?|id|no\.?)?|imps\s*(?:ref(?:erence)?\s*(?:no\.?)?)?|rrn|ref(?:erence)?\s*(?:no\.?|num)?)\s*[:\s-]*([A-Z0-9]{8,22})"""
    )

    // Generic banking terms to reject in merchant extraction (sms_parser.py line 198)
    private val GENERIC_MERCHANT_TERMS = listOf(
        "bank", "account", "acct", "a/c", "upi", "ref", "card", "your",
        "avl bal", "balance", "txn", "transaction", "payment"
    )

    /**
     * Cleans currency amount string removing commas and whitespace.
     */
    fun cleanAmount(amtStr: String): Double {
        val match = Regex("""(\d+(?:,\d+)*(?:\.\d+)?)""").find(amtStr)
        return match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
    }

    /**
     * Parses an incoming SMS text on-device.
     * Enforces bank sender whitelist before parsing.
     *
     * @param rawSms Raw SMS body
     * @param sender Sender header (e.g. "AD-HDFCBK")
     * @return ParsedSmsTransaction containing structured fields, or null if spam/non-transactional/rejected.
     */
    fun parse(rawSms: String, sender: String, timestampMillis: Long = System.currentTimeMillis()): ParsedSmsTransaction? {
        // Enforce bank whitelist: non-whitelisted senders never reach parser
        if (!BankSenderWhitelist.isWhitelisted(sender)) {
            return null
        }

        val sms = rawSms.replace(Regex("""\s+"""), " ").trim()
        val smsLower = sms.lowercase(Locale.ROOT)

        // 0. Reject marketing, promotional, OTP, loan offers, mandate requests
        for (pat in SPAM_PATTERNS) {
            if (pat.containsMatchIn(smsLower)) {
                return null
            }
        }

        // Determine debit vs credit
        val isDebit = DEBIT_KEYWORDS.any { smsLower.contains(it) }
        val isCredit = CREDIT_KEYWORDS.any { smsLower.contains(it) }

        if (!isDebit && !isCredit) {
            return null
        }

        val txType = if (isDebit) "debit" else "credit"
        val bank = BankSenderWhitelist.identifyBank(sender, sms)

        // 1. Amount Extraction
        val amtMatch = AMOUNT_PATTERN.find(sms) ?: return null
        val amountStr = amtMatch.groupValues[1].takeIf { it.isNotEmpty() }
            ?: amtMatch.groupValues[2].takeIf { it.isNotEmpty() }
            ?: return null

        val amount = cleanAmount(amountStr)
        if (amount <= 0.0) {
            return null
        }

        // 2. Account Last 4 Extraction — try primary pattern, then fallback
        val acctMatch = ACCT_PATTERN.find(sms)
            ?: ACCT_PATTERN_FALLBACK.find(sms)
        val accountLast4 = acctMatch?.groupValues?.get(1) ?: "0000"

        // 3. Merchant / Payee Extraction
        var merchant = "Unknown Merchant"
        for (pat in MERCH_PATTERNS) {
            val match = pat.find(sms)
            if (match != null) {
                var candidate = match.groupValues[1].trim()
                candidate = MERCH_PREFIX_CLEANUP.replace(candidate, "").trim()
                val candidateLower = candidate.lowercase(Locale.ROOT)

                val isAmount = Regex("""^(?:rs\.?|inr|₹)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE).containsMatchIn(candidateLower)
                        || Regex("""^[\d,]+(?:\.\d+)?""").containsMatchIn(candidateLower)

                val isGeneric = GENERIC_MERCHANT_TERMS.any { term ->
                    candidateLower == term || candidateLower.startsWith("$term ") || candidateLower.startsWith("$term/")
                }

                if (candidate.length > 2 && !isAmount && !isGeneric) {
                    merchant = candidate
                    break
                }
            }
        }

        // 4. Date Extraction: Use the exact SMS reception timestamp for microsecond precision deduplication!
        val parsedDateIso = OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestampMillis),
            ZoneOffset.UTC
        ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        // 5. UPI / IMPS Ref Extraction
        val upiMatch = UPI_REF_PATTERN.find(sms)
        val upiRef = upiMatch?.groupValues?.get(1)?.take(100)

        return ParsedSmsTransaction(
            amount = amount,
            transactionType = txType,
            merchantRaw = merchant,
            bankSenderId = sender.take(50),
            accountLast4 = accountLast4,
            date = parsedDateIso,
            upiRef = upiRef,
            bank = bank
        )
    }

}
