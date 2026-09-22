package com.smartspend.app.ui.components

import com.smartspend.app.TransactionData
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

private val IndiaLocale: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()

private val inrWhole: NumberFormat = NumberFormat.getCurrencyInstance(IndiaLocale)
    .apply { maximumFractionDigits = 0 }

private val inrExact: NumberFormat = NumberFormat.getCurrencyInstance(IndiaLocale)
    .apply { minimumFractionDigits = 0; maximumFractionDigits = 2 }

/** Whole rupees, Indian grouping (₹1,23,456). Paise add width and tell the reader nothing. */
fun money(value: Double): String = inrWhole.format(value.absoluteValue)

/** For the one place paise matter: a single transaction's own amount. */
fun moneyExact(value: Double): String = inrExact.format(value.absoluteValue)

/** Axis-tick sized: ₹950, ₹12k, ₹1.2L. */
fun moneyShort(value: Double): String {
    val v = value.absoluteValue
    return when {
        v >= 1_00_000 -> "₹" + trimZero(v / 1_00_000) + "L"
        v >= 1_000 -> "₹" + trimZero(v / 1_000) + "k"
        else -> "₹" + v.toLong()
    }
}

private fun trimZero(v: Double): String {
    val s = String.format(Locale.US, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

fun parseTxDate(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
}

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)

fun shortDate(raw: String?): String {
    val d = parseTxDate(raw) ?: return ""
    return if (d.year == LocalDate.now().year) d.format(dayMonth) else d.format(dayMonthYear)
}

/** "Today", "Yesterday", or "Monday, 14 Sep" — the header over a day's transactions. */
fun dayHeader(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) date.format(weekdayDayMonth) else date.format(dayMonthYear)
    }
}

fun monthName(month: Int, year: Int, pattern: String = "MMMM"): String =
    runCatching { YearMonth.of(year, month).format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) }
        .getOrDefault("")

val TransactionData.isCredit: Boolean get() = type.equals("credit", ignoreCase = true)

/**
 * `source` records how the categoriser decided (merchant map, keyword, user correction…),
 * not where the row came from, so it can't tell SMS from manual. SMS rows always carry the
 * sending bank or card digits; hand-entered ones never do.
 */
val TransactionData.isFromSms: Boolean
    get() = !bank.isNullOrBlank() || !account_last4.isNullOrBlank()

val TransactionData.needsReview: Boolean
    get() = review_status?.contains("needs_review", ignoreCase = true) == true ||
        category.equals("Needs Review", ignoreCase = true)

val TransactionData.displayName: String
    get() = merchant?.takeIf { it.isNotBlank() } ?: category

fun greetingFor(hour: Int): String = when (hour) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
