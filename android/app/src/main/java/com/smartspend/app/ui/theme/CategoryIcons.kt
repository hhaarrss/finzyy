package com.smartspend.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/**
 * The backend's canonical categories (backend/constants/categories.py): 17 debit, 8 credit,
 * plus the "Needs Review" placeholder the UI also shows. Kept as the one list both the icon
 * and colour lookups are keyed on.
 */
object Categories {
    const val FOOD = "Food & Dining"
    const val GROCERIES = "Groceries"
    const val TRANSPORTATION = "Transportation"
    const val SHOPPING = "Shopping"
    const val ENTERTAINMENT = "Entertainment"
    const val UTILITIES = "Utilities & Bills"
    const val HEALTHCARE = "Healthcare"
    const val EDUCATION = "Education"
    const val TRAVEL = "Travel & Hotels"
    const val FUEL = "Fuel"
    const val SUBSCRIPTIONS = "Subscriptions"
    const val TELECOM = "Telecom & Recharge"
    const val FINANCE = "Finance & Insurance"
    const val PERSONAL_CARE = "Personal Care"
    const val RENT = "Rent"
    const val TRANSFER = "Transfer"
    const val OTHER = "Other"

    const val SALARY = "Salary"
    const val REFUND = "Refund"
    const val INTEREST = "Interest"
    const val BANK_DEPOSIT = "Bank Deposit"
    const val INVESTMENT_RETURN = "Investment Return"
    const val REIMBURSEMENT = "Reimbursement"
    const val CASHBACK = "Cashback"
    const val OTHER_CREDIT = "Other Credit"

    const val NEEDS_REVIEW = "Needs Review"

    val debit = listOf(
        FOOD, GROCERIES, TRANSPORTATION, SHOPPING, ENTERTAINMENT, UTILITIES, HEALTHCARE,
        EDUCATION, TRAVEL, FUEL, SUBSCRIPTIONS, TELECOM, FINANCE, PERSONAL_CARE, RENT,
        TRANSFER, OTHER
    )
    val credit = listOf(
        SALARY, REFUND, INTEREST, BANK_DEPOSIT, INVESTMENT_RETURN, REIMBURSEMENT, CASHBACK, OTHER_CREDIT
    )
    val all = debit + credit + NEEDS_REVIEW

    private val byLowercase = all.associateBy { it.lowercase(Locale.ROOT) }

    /**
     * Legacy and shorthand spellings, mirroring CANONICAL_CATEGORY_MAP in
     * backend/categorizer/transaction_categorizer.py. Rows written before write-time
     * normalisation (and seed data) still carry these.
     */
    private val aliases = mapOf(
        "food" to FOOD, "food and dining" to FOOD, "dining" to FOOD,
        "travel" to TRANSPORTATION, "transport" to TRANSPORTATION, "cab" to TRANSPORTATION,
        "travel and hotels" to TRAVEL, "hotels" to TRAVEL, "hotel" to TRAVEL,
        "bills" to UTILITIES, "utilities" to UTILITIES,
        "recharge" to TELECOM,
        "finance" to FINANCE,
        "miscellaneous" to OTHER,
        "needs_review" to NEEDS_REVIEW,
        "reversed" to REFUND, "reversal" to REFUND,
        "interest credited" to INTEREST,
        "bank_deposit" to BANK_DEPOSIT, "deposit" to BANK_DEPOSIT,
        "investment_return" to INVESTMENT_RETURN, "investment" to INVESTMENT_RETURN, "dividend" to INVESTMENT_RETURN,
        "reward" to CASHBACK, "rewards" to CASHBACK,
        "other_credit" to OTHER_CREDIT
    )

    /** Canonical name for any spelling the backend or old rows may send; null if unknown. */
    fun canonical(raw: String?): String? {
        val key = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return byLowercase[key] ?: aliases[key]
    }
}

private val iconByCategory: Map<String, ImageVector> = mapOf(
    Categories.FOOD to Icons.Rounded.Restaurant,
    Categories.GROCERIES to Icons.Rounded.LocalGroceryStore,
    Categories.TRANSPORTATION to Icons.Rounded.LocalTaxi,
    Categories.SHOPPING to Icons.Rounded.ShoppingBag,
    Categories.ENTERTAINMENT to Icons.Rounded.Movie,
    Categories.UTILITIES to Icons.AutoMirrored.Rounded.ReceiptLong,
    Categories.HEALTHCARE to Icons.Rounded.MedicalServices,
    Categories.EDUCATION to Icons.Rounded.School,
    Categories.TRAVEL to Icons.Rounded.Luggage,
    Categories.FUEL to Icons.Rounded.LocalGasStation,
    Categories.SUBSCRIPTIONS to Icons.Rounded.Subscriptions,
    Categories.TELECOM to Icons.Rounded.PhoneAndroid,
    Categories.FINANCE to Icons.Rounded.Shield,
    Categories.PERSONAL_CARE to Icons.Rounded.Spa,
    Categories.RENT to Icons.Rounded.Home,
    Categories.TRANSFER to Icons.Rounded.SwapHoriz,
    Categories.OTHER to Icons.Rounded.Category,

    Categories.SALARY to Icons.Rounded.Payments,
    Categories.REFUND to Icons.AutoMirrored.Rounded.Undo,
    Categories.INTEREST to Icons.Rounded.Percent,
    Categories.BANK_DEPOSIT to Icons.Rounded.AccountBalance,
    Categories.INVESTMENT_RETURN to Icons.AutoMirrored.Rounded.TrendingUp,
    Categories.REIMBURSEMENT to Icons.Rounded.RequestQuote,
    Categories.CASHBACK to Icons.Rounded.Redeem,
    Categories.OTHER_CREDIT to Icons.Rounded.AccountBalanceWallet,

    Categories.NEEDS_REVIEW to Icons.Rounded.QuestionMark
)

/**
 * The single category → icon lookup, used by every screen that shows a category. Never null:
 * a category the app doesn't know yet (the backend can add one without a release) gets the
 * generic "Other" icon rather than a blank chip.
 */
fun CategoryIcon(category: String?): ImageVector =
    Categories.canonical(category)?.let { iconByCategory[it] } ?: Icons.Rounded.Category
