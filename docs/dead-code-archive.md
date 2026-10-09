# Dead code archive

Code that was removed from the live source because **nothing references it**. It is kept here so it can be brought back if needed. This file is Markdown, so none of it is compiled, imported or shipped.

**Removed from the source tree in the commit that added this file.** Exact originals are in git at `56a5f6c` (the parent of that commit): `git show 56a5f6c:<path>`.

## How to bring something back

1. Find the entry below and copy the code block into the *original path* shown (for a function or class, put it back inside the same file, or the same object/interface).
2. Add any imports the code needs (the compiler / `pyflakes` will list them).
3. Build and run the tests. The exact original is always available with `git show` (above).
4. Delete the entry from this file.

## Before you restore a backend route or an app API call

The app and the server must agree. The app is in `android/`, the server in `backend/`; check that the other side still has the matching endpoint.

## Index

| # | Group | Item | Original file |
|---|---|---|---|
| 1 | Android | Retrofit call `categorizeTransaction` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 2 | Android | Retrofit call `getCategories` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 3 | Android | Retrofit call `register` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 4 | Android | Retrofit call `getTransactions` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 5 | Android | Retrofit call `getMonthlyCategorySummary` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 6 | Android | Retrofit call `getMerchants` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 7 | Android | Retrofit call `getCategorySummary` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 8 | Android | Retrofit call `getBudgets` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 9 | Android | Retrofit call `getBudgetsNoAuth` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 10 | Android | Retrofit call `setBudget` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 11 | Android | Retrofit call `createTransaction` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 12 | Android | Retrofit call `getInsightsSummary` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 13 | Android | Model `CategorizePayload` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 14 | Android | Model `RegisterPayload` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 15 | Android | Model `CategoriesResponse` | `android/app/src/main/java/com/smartspend/app/BackendService.kt` |
| 16 | Android | Model `MerchantData` | `android/app/src/main/java/com/smartspend/app/ApiModels.kt` |
| 17 | Android | `SmsFilter.kt` (whole file) | `android/app/src/main/java/com/smartspend/app/SmsFilter.kt` |
| 18 | Android | `SmsReceiver.hasQueuedSms` | `android/app/src/main/java/com/smartspend/app/SmsReceiver.kt` |
| 19 | Android | `SmsTransactionParser.parseSmsDate` | `android/app/src/main/java/com/smartspend/app/sms/SmsTransactionParser.kt` |
| 20 | Android | `SmsTransactionParser.DATE_PATTERN` | `android/app/src/main/java/com/smartspend/app/sms/SmsTransactionParser.kt` |
| 21 | Android | `ChartTitleValue` composable | `android/app/src/main/java/com/smartspend/app/ui/components/Charts.kt` |
| 22 | Android | `Standard` easing curve | `android/app/src/main/java/com/smartspend/app/ui/onboarding/OnboardingTheme.kt` |
| 23 | Android | `TransactionData.displayName` | `android/app/src/main/java/com/smartspend/app/ui/components/Format.kt` |
| 24 | Android | `SmartSpendIcons.Split` icon | `android/app/src/main/java/com/smartspend/app/ui/components/SmartSpendIcons.kt` |
| 25 | Backend | `utils/categorizer.py` (whole file) | `backend/utils/categorizer.py` |
| 26 | Backend | `check_db.py` (whole file) | `backend/check_db.py` |
| 27 | Backend | `process_upi_sms`, `process_batch`, `run_demo` and the `__main__` demo | `backend/categorizer/transaction_categorizer.py` |
| 28 | Backend | Shadowed route decorator on `categorize_transaction_item` | `backend/routers/transactions.py` |
| 29 | Repo root | `test_regex.kt` (regex only) | `test_regex.kt` |
| 30 | Android resources | 28 unused color resources in `values/colors.xml` | `android/app/src/main/res/values/colors.xml` |
| 31 | Android resources | 14 unused color resources in `values-night/colors.xml` | `android/app/src/main/res/values-night/colors.xml` |
| 32 | Android resources | 18 unused string resources in `values/strings.xml` | `android/app/src/main/res/values/strings.xml` |
| 33 | Android resources | Drawable `ic_smartspend_logo.xml` | `android/app/src/main/res/drawable/ic_smartspend_logo.xml` |
| 34 | Android resources | Drawable `login_field_bg.xml` | `android/app/src/main/res/drawable/login_field_bg.xml` |


---

## 1. Retrofit call `categorizeTransaction`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 296-304)
- **Why it is dead:** No caller anywhere: PATCH categorize; the app uses recategorizeTransaction.

```kotlin
    /**
     * 1-click categorize transaction and record merchant learning.
     */
    @PATCH("transactions/{id}/categorize")
    suspend fun categorizeTransaction(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body payload: CategorizePayload
    ): Response<Map<String, Any>>
```

---

## 2. Retrofit call `getCategories`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 315-319)
- **Why it is dead:** No caller anywhere: duplicate of getCategoryLists (same route); nothing calls it.

```kotlin
    /**
     * Fetch single canonical category list.
     */
    @GET("categories")
    suspend fun getCategories(): Response<CategoriesResponse>
```

---

## 3. Retrofit call `register`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 353-356)
- **Why it is dead:** No caller anywhere: email sign-up; the app signs up with phone or Google.

```kotlin
    @POST("auth/register")
    suspend fun register(
        @Body payload: RegisterPayload
    ): Response<AuthResponse>
```

---

## 4. Retrofit call `getTransactions`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 383-396)
- **Why it is dead:** No caller anywhere: explicit-token twin of getTransactionsNoAuth, which the app uses.

```kotlin
    /**
     * Fetch user's transactions list.
     */
    @GET("transactions/")
    suspend fun getTransactions(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("month") month: Int? = null,
        @Query("year") year: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("include_transfers") includeTransfers: Boolean? = null
    ): Response<PaginatedTransactionResponse>
```

---

## 5. Retrofit call `getMonthlyCategorySummary`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 411-419)
- **Why it is dead:** No caller anywhere: twin of getMonthlyCategorySummaryNoAuth.

```kotlin
    /**
     * Fetch monthly category spending summary with budget utilization.
     */
    @GET("transactions/monthly-category-summary")
    suspend fun getMonthlyCategorySummary(
        @Header("Authorization") token: String,
        @Query("month") month: Int,
        @Query("year") year: Int
    ): Response<MonthlyCategorySummaryResponse>
```

---

## 6. Retrofit call `getMerchants`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 427-428)
- **Why it is dead:** No caller anywhere: nothing calls GET /transactions/merchants.

```kotlin
    @GET("transactions/merchants")
    suspend fun getMerchants(): Response<List<MerchantData>>
```

---

## 7. Retrofit call `getCategorySummary`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 430-437)
- **Why it is dead:** No caller anywhere: nothing calls GET /transactions/summary.

```kotlin
    /**
     * Fetch category totals summary for a given month (YYYY-MM).
     */
    @GET("transactions/summary")
    suspend fun getCategorySummary(
        @Header("Authorization") token: String,
        @Query("month") month: String
    ): Response<Map<String, Double>>
```

---

## 8. Retrofit call `getBudgets`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 439-445)
- **Why it is dead:** No caller anywhere: nothing lists budgets through this call.

```kotlin
    /**
     * Fetch configured budget limits.
     */
    @GET("budget/")
    suspend fun getBudgets(
        @Header("Authorization") token: String
    ): Response<List<BudgetLimitData>>
```

---

## 9. Retrofit call `getBudgetsNoAuth`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 447-448)
- **Why it is dead:** No caller anywhere: nothing lists budgets through this call.

```kotlin
    @GET("budget/")
    suspend fun getBudgetsNoAuth(): Response<List<BudgetLimitData>>
```

---

## 10. Retrofit call `setBudget`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 461-468)
- **Why it is dead:** No caller anywhere: twin of setBudgetNoAuth.

```kotlin
    /**
     * Create or update category budget limit.
     */
    @POST("budget/")
    suspend fun setBudget(
        @Header("Authorization") token: String,
        @Body payload: BudgetSetPayload
    ): Response<BudgetLimitData>
```

---

## 11. Retrofit call `createTransaction`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 475-482)
- **Why it is dead:** No caller anywhere: twin of createTransactionNoAuth.

```kotlin
    /**
     * Manually create a transaction.
     */
    @POST("transactions/")
    suspend fun createTransaction(
        @Header("Authorization") token: String,
        @Body payload: TransactionCreatePayload
    ): Response<TransactionData>
```

---

## 12. Retrofit call `getInsightsSummary`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 499-505)
- **Why it is dead:** No caller anywhere: twin of getInsightsSummaryNoAuth.

```kotlin
    /**
     * Fetch analytical financial insights summary.
     */
    @GET("insights/summary")
    suspend fun getInsightsSummary(
        @Header("Authorization") token: String
    ): Response<InsightsSummaryData>
```

---

## 13. Model `CategorizePayload`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 250-253)
- **Why it is dead:** Orphaned: only used by the removed categorizeTransaction.

```kotlin
data class CategorizePayload(
    val category: String,
    val merchant_alias: String? = null
)
```

---

## 14. Model `RegisterPayload`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 101-108)
- **Why it is dead:** Orphaned: only used by the removed register.

```kotlin
/**
 * Payload for registering a new user.
 */
data class RegisterPayload(
    val email: String,
    val full_name: String,
    val password: String
)
```

---

## 15. Model `CategoriesResponse`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/BackendService.kt` (was around lines 234-238)
- **Why it is dead:** Orphaned: only used by the removed getCategories.

```kotlin
data class CategoriesResponse(
    val categories: List<String> = emptyList(),
    val debit: List<String> = emptyList(),
    val credit: List<String> = emptyList()
)
```

---

## 16. Model `MerchantData`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/ApiModels.kt` (was around lines 55-59)
- **Why it is dead:** Orphaned: only used by the removed getMerchants.

```kotlin
data class MerchantData(
    val name: String,
    val category: String?,
    val count: Int
)
```

---

## 17. `SmsFilter.kt` (whole file)

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/SmsFilter.kt` (was around lines 1-57)
- **Why it is dead:** Old keyword-based SMS filter. Replaced by SmsTransactionParser + BankSenderWhitelist; only a comment referred to it.

```kotlin
package com.smartspend.app

import java.util.Locale

object SmsFilter {
    private val bankSenderIds = listOf(
        "HDFCBK", "ICICIB", "SBIPSG", "SBIINB", "AXISBK", "KOTAKB", "YESBNK",
        "PNBSMS", "BOBSMS", "CANBNK", "INDBNK", "IDFCFB", "RBLBNK", "CITIBK",
        "AMEXIN", "ONECRD", "FEDBNK", "UNIONB", "PAYTMB", "GPAY", "BHIM",
        "AD-BANK"
    )

    private val spamKeywords = listOf(
        "save rs", "earn up to", "cashback every", "apply now", "pre-approved",
        "pre approved", "loan offer", "get up to", "win up to", "lifetime free",
        "at no extra charge", "pro pass", "voucher", "coupon", "discount on",
        "mandate collect request", "request for blocking of funds",
        "otp", "verification code", "do not share", "claim now", "offer ends",
        "congratulations", "credit card limit", "personal loan"
    )

    private val actionKeywords = listOf(
        "debited", "credited", "transferred", "spent", "paid", "withdrawn",
        "deposited", "sent to", "received from", "received rs", "credited with", "refund"
    )

    private val currencyKeywords = listOf(
        "rs.", "rs ", "inr", "₹"
    )

    fun isTransactional(sender: String, body: String): Boolean {
        val sLower = sender.lowercase(Locale.ROOT)
        val bLower = body.lowercase(Locale.ROOT)

        // 1. Immediately reject spam, promotional, OTP, or mandate request messages
        if (spamKeywords.any { bLower.contains(it) }) {
            return false
        }

        // 2. Must contain an amount indicator (Rs., INR, ₹)
        val hasCurrency = currencyKeywords.any { bLower.contains(it) }
        if (!hasCurrency) {
            return false
        }

        // 3. Must contain a concrete transaction action (debited, credited, paid, etc.)
        val hasAction = actionKeywords.any { bLower.contains(it) }
        if (!hasAction) {
            return false
        }

        // 4. Must originate from the known bank sender whitelist.
        return bankSenderIds.any { sLower.contains(it.lowercase(Locale.ROOT)) }
    }
}
```

---

## 18. `SmsReceiver.hasQueuedSms`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/SmsReceiver.kt` (was around lines 140-149)
- **Why it is dead:** No caller.

```kotlin
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
```

---

## 19. `SmsTransactionParser.parseSmsDate`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/sms/SmsTransactionParser.kt` (was around lines 242-273)
- **Why it is dead:** No caller (dates are read another way).

```kotlin
    /**
     * Parses a date string from an SMS and converts it to ISO-8601 UTC timestamp.
     * Supports formats: DD-MM-YY, DD-MM-YYYY, DD/MM/YY, DD/MM/YYYY, DD-MMM-YY, etc.
     */
    fun parseSmsDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        var cleaned = dateStr.trim().replace(".", "-").replace("/", "-")
        cleaned = Regex("""(\d+)(st|nd|rd|th)""", RegexOption.IGNORE_CASE).replace(cleaned, "$1")

        val formatters = listOf(
            DateTimeFormatterBuilder().appendPattern("dd-MM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
            DateTimeFormatterBuilder().appendPattern("dd-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, 2000).toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMMM-yyyy", Locale.ENGLISH)
        )

        for (formatter in formatters) {
            try {
                val localDate = LocalDate.parse(cleaned, formatter)
                return localDate.atStartOfDay().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (_: Exception) {
                // Continue to next formatter
            }
        }

        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
```

---

## 20. `SmsTransactionParser.DATE_PATTERN`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/sms/SmsTransactionParser.kt` (was around lines 123-124)
- **Why it is dead:** No reference.

```kotlin
    // Date extraction regex from sms_parser.py line 205
    private val DATE_PATTERN = Regex("""(\d{1,2}[\/\-\.](?:\d{1,2}|[A-Za-z]{3})[\/\-\.]\d{2,4})""")
```

---

## 21. `ChartTitleValue` composable

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/ui/components/Charts.kt` (was around lines 269-280)
- **Why it is dead:** No caller.

```kotlin
@Composable
fun ChartTitleValue(caption: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Eyebrow(caption)
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
```

---

## 22. `Standard` easing curve

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/ui/onboarding/OnboardingTheme.kt` (was around lines 64-64)
- **Why it is dead:** No reference.

```kotlin
internal val Standard: Easing = FastOutSlowInEasing
```

---

## 23. `TransactionData.displayName`

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/ui/components/Format.kt` (was around lines 85-86)
- **Why it is dead:** No reference (TxView.title does this job).

```kotlin
val TransactionData.displayName: String
    get() = merchant?.takeIf { it.isNotBlank() } ?: category
```

---

## 24. `SmartSpendIcons.Split` icon

- **Group:** Android
- **Original file:** `android/app/src/main/java/com/smartspend/app/ui/components/SmartSpendIcons.kt` (was around lines 49-57)
- **Why it is dead:** No reference (the Splits screen was UI-only and never shipped).

```kotlin
    val Split: ImageVector by lazy {
        stroked("Split") {
            circle(9f, 8f, 3.2f)
            moveTo(3f, 19.5f); curveTo(3f, 16f, 5.7f, 14f, 9f, 14f)
            curveTo(12.3f, 14f, 15f, 16f, 15f, 19.5f)
            circle(17f, 9f, 2.5f)
            moveTo(17f, 14f); curveTo(19.4f, 14f, 21f, 15.8f, 21f, 18.5f)
        }
    }
```

---

## 25. `utils/categorizer.py` (whole file)

- **Group:** Backend
- **Original file:** `backend/utils/categorizer.py` (was around lines 1-155)
- **Why it is dead:** Old keyword-to-category mapper. Nothing imports it; the categoriser lives in categorizer/transaction_categorizer.py.

```python
"""
Categorizer utility for transaction merchants.

Maps parsed merchant names to standard expense categories based on keyword mappings.
"""

from typing import Optional

# Mappings of keywords to categories
MERCHANT_MAPPINGS = {
    # Food
    "swiggy": "Food",
    "zomato": "Food",
    "ubereats": "Food",
    "dominos": "Food",
    "pizza hut": "Food",
    "kfc": "Food",
    "mcdonald": "Food",
    "starbucks": "Food",
    "burger king": "Food",
    "haldiram": "Food",
    "faasos": "Food",
    "behrouz": "Food",
    "chai point": "Food",
    "subway": "Food",
    
    # Travel & Transport
    "irctc": "Travel",
    "makemytrip": "Travel",
    "goibibo": "Travel",
    "uber": "Travel",
    "ola": "Travel",
    "rapido": "Travel",
    "redbus": "Travel",
    "yatra": "Travel",
    "cleartrip": "Travel",
    "easemytrip": "Travel",
    "indigo": "Travel",
    "air india": "Travel",
    "spicejet": "Travel",
    "namma yatri": "Travel",
    
    # Shopping
    "amazon": "Shopping",
    "flipkart": "Shopping",
    "myntra": "Shopping",
    "ajio": "Shopping",
    "nykaa": "Shopping",
    "meesho": "Shopping",
    "zara": "Shopping",
    "h&m": "Shopping",
    "decathlon": "Shopping",
    "tata cliq": "Shopping",
    "croma": "Shopping",
    "reliance digital": "Shopping",
    "marks & spencer": "Shopping",
    "lifestyle": "Shopping",
    
    # Utilities
    "bescom": "Utilities",
    "bses": "Utilities",
    "adani electricity": "Utilities",
    "tata power": "Utilities",
    "airtel": "Utilities",
    "jio": "Utilities",
    "vi ": "Utilities",
    "act fibernet": "Utilities",
    "hathway": "Utilities",
    "indane": "Utilities",
    "bharat gas": "Utilities",
    "hp gas": "Utilities",
    "electricity": "Utilities",
    "broadband": "Utilities",
    "billdesk": "Utilities",
    "water board": "Utilities",
    
    # Entertainment
    "netflix": "Entertainment",
    "prime video": "Entertainment",
    "primevideo": "Entertainment",
    "hotstar": "Entertainment",
    "zee5": "Entertainment",
    "sonyliv": "Entertainment",
    "bookmyshow": "Entertainment",
    "spotify": "Entertainment",
    "gaana": "Entertainment",
    "youtube premium": "Entertainment",
    
    # Healthcare
    "netmeds": "Healthcare",
    "1mg": "Healthcare",
    "apollo": "Healthcare",
    "pharmeasy": "Healthcare",
    "medplus": "Healthcare",
    "practo": "Healthcare",
    "dr lal pathlabs": "Healthcare",
    "healthkart": "Healthcare",
    
    # Education
    "coursera": "Education",
    "udemy": "Education",
    "edx": "Education",
    "byjus": "Education",
    "unacademy": "Education",
    "simplilearn": "Education",
    "udacity": "Education",
    
    # Fuel
    "indian oil": "Fuel",
    "iocl": "Fuel",
    "hpcl": "Fuel",
    "bpcl": "Fuel",
    "shell": "Fuel",
    "fuel": "Fuel",
    "petrol": "Fuel",
    "cng": "Fuel",
    
    # Groceries
    "blinkit": "Groceries",
    "zepto": "Groceries",
    "instamart": "Groceries",
    "bigbasket": "Groceries",
    "jiomart": "Groceries",
    "dmart": "Groceries",
    "spencers": "Groceries",
    "star bazaar": "Groceries",
    "more retail": "Groceries",
    "groceries": "Groceries",
    "grocery": "Groceries"
}


def categorize_merchant(merchant_name: Optional[str]) -> str:
    """
    Categorizes a transaction based on the merchant name using keyword matching.

    Args:
        merchant_name (Optional[str]): The name of the merchant from the transaction.

    Returns:
        str: The categorized group ('Food', 'Travel', 'Shopping', etc.).
             Defaults to 'Other' if no match is found.
    """
    if not merchant_name:
        return "Other"
        
    merchant_normalized = merchant_name.lower().strip()
    
    # Find matching keyword
    for keyword, category in MERCHANT_MAPPINGS.items():
        if keyword in merchant_normalized:
            return category
            
    return "Other"
```

---

## 26. `check_db.py` (whole file)

- **Group:** Backend
- **Original file:** `backend/check_db.py` (was around lines 1-12)
- **Why it is dead:** Developer scratch script that prints rows; nothing runs it.
- **Note:** **The original contained a real database password, committed in `f0cad46` (21 Sep 2026). It is redacted here. That password is still in git history, so treat it as leaked: rotate it or delete that database.**

```python
import asyncio
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy import text
async def main():
    engine = create_async_engine('postgresql://neondb_owner:<PASSWORD-REDACTED>@ep-floral-grass-a5s8r23e.us-east-2.aws.neon.tech/neondb?sslmode=require', echo=False)
    async with engine.connect() as conn:
        result = await conn.execute(text('SELECT amount, merchant_raw, created_at FROM transactions ORDER BY created_at DESC LIMIT 5'))
        for row in result:
            print(row)
    await engine.dispose()
asyncio.run(main())
```

---

## 27. `process_upi_sms`, `process_batch`, `run_demo` and the `__main__` demo

- **Group:** Backend
- **Original file:** `backend/categorizer/transaction_categorizer.py` (was around lines 700-763)
- **Why it is dead:** Server-side raw-SMS entry points. Only the demo ever called them; production receives structured fields parsed on the phone.

```python
def process_upi_sms(sms: str) -> Optional[Dict[str, Any]]:
    """
    Parse SMS and categorize in a single call.
    """
    parsed = parse_sms(sms)
    if not parsed:
        return None
    return {**parsed, **categorize_transaction(parsed.get("merchant_raw"))}


# ─────────────────────────────────────────────
# 5. BATCH PROCESSING
# ─────────────────────────────────────────────

# ─────────────────────────────────────────────
# 6. BATCH PROCESSING
# ─────────────────────────────────────────────

def process_batch(sms_list: List[str]) -> List[Dict[str, Any]]:
    """
    Process a batch of SMS messages.
    """
    results = [process_upi_sms(sms) for sms in sms_list]
    return [r for r in results if r is not None]


# ─────────────────────────────────────────────
# 7. DEMO / TEST
# ─────────────────────────────────────────────

def run_demo() -> None:
    """
    Run a diagnostic demo to test parsing and categorization.
    """
    print("\n" + "=" * 50)
    print("       UPI SMS Transaction Categorizer - Demo")
    print("=" * 50 + "\n")

    test_sms_list = [
        "Rs.349 debited from SBI A/c XX1234 on 09-07-26. Info: UPI-SWIGGY-Swiggy Order. Avail Bal: Rs.12,456.78",
        "Your A/c XXXX5678 debited by Rs.1,299 on 09Jul26. UPI Ref 987654321. Info: UPI-NETFLIX-Netflix Subscription",
        "Dear Customer, Rs.500.00 has been debited from your account. Merchant: BPCL PETROL PUMP. Ref: 112233445",
        "Sent Rs 150 to ZOMATO INDIA PVT LTD via UPI on 09/07/2026. UPI Ref: 445566778",
        "INR 2500 paid to IRCTC via UPI. Txn ID: 998877665544. Your train ticket is confirmed.",
        "Rs.89 debited. Info: UPI-SPOTIFY-Monthly Plan. Ref No: 554433221",
        "Payment of Rs.45 to UNKNOWN KIRANA SHOP via UPI successful. Ref: 667788990",
        "Rs.12,000 credited to your account from HDFC SALARY. Ref: 223344556",
    ]

    print("Processing SMS messages...\n")

    for i, sms in enumerate(test_sms_list):
        result = process_upi_sms(sms)
        if result:
            print(f"[{i + 1}] SMS: \"{sms[:60]}...\"")
            print(f"     Amount   : Rs. {result.get('amount') or 'N/A'}")
            print(f"     Merchant : {result.get('merchant') or 'Unknown'}")
            print(f"     Category : {result.get('category')}" + (f" -> {result['subcategory']}" if result.get('subcategory') else ""))
            print(f"     Source   : {result.get('source')} ({result.get('confidence')} confidence)")
            print(f"     Type     : {result.get('type') or 'unknown'}")
            print()

if __name__ == "__main__":
    run_demo()
```

---

## 28. Shadowed route decorator on `categorize_transaction_item`

- **Group:** Backend
- **Original file:** `backend/routers/transactions.py` (was around lines 979-982)
- **Why it is dead:** PATCH /transactions/{id}/recategorize is registered earlier by `recategorize_transaction`, which always wins; this second registration was unreachable.
- **Note:** The function below it (`categorize_transaction_item`) stays, still served at PATCH /{transaction_id}/categorize.

```python
@router.patch(
    "/{transaction_id}/recategorize",
    summary="Re-categorize transaction alias endpoint"
)
```

---

## 29. `test_regex.kt` (regex only)

- **Group:** Repo root
- **Original file:** `test_regex.kt` (was around lines 1-9)
- **Why it is dead:** Scratch file with a `main()`; not part of any module.

```kotlin
// Scratch file for trying the UPI-reference regex. The sample SMS was left out (it contained a real name).
val pat = Regex("(?i)(?:upi\\s*(?:ref(?:erence)?\\s*(?:no\\.?|num\\.?|number)?|id|no\\.?)?|imps\\s*(?:ref(?:erence)?\\s*(?:no\\.?)?)?|rrn\\s*[:\\s-]*|ref(?:erence)?\\s*(?:no\\.?|num)?\\s*[:\\s-]*)\\s*([A-Za-z0-9]{8,22})")
```

---

## 30. 28 unused color resources in `values/colors.xml`

- **Group:** Android resources
- **Original file:** `android/app/src/main/res/values/colors.xml`
- **Why it is dead:** Lint reports them unused: left over from the old XML login/dashboard screens.

```xml
<color name="dark_surface">#FFFFFF</color> <!-- White surface -->
<color name="dark_card">#FFFFFF</color> <!-- White cards -->
<color name="dark_input">#F8FAFC</color> <!-- Slate 50 inputs -->
<color name="dark_border">#E2E8F0</color> <!-- Slate 200 borders -->
<color name="indigo_light">#EAF7EF</color> <!-- Light Emerald bg -->
<color name="indigo_surface">#1A16803C</color> <!-- Semi-transparent green -->
<color name="emerald_success">#22A447</color>
<color name="emerald_surface">#EAF7EF</color>
<color name="rose_error">#EF4444</color>
<color name="rose_surface">#FEF2F2</color>
<color name="amber_warning">#F59E0B</color>
<color name="text_tertiary">#64748B</color> <!-- Slate 500 -->
<color name="login_background">#FFF1F1EE</color>
<color name="login_surface">#FFFFFFFF</color>
<color name="login_ink">#FF0A0A0A</color>
<color name="login_ink_soft">#FF6B6B66</color>
<color name="login_field">#FFF7F7F4</color>
<color name="login_hairline">#FFE1E1DC</color>
<color name="login_brand">#FF0A0A0A</color>
<color name="login_brand_disabled">#800A0A0A</color>
<color name="login_on_brand">#FFFFFFFF</color>
<color name="login_hero_top">#FF1C1C1C</color>
<color name="login_hero_bottom">#FF000000</color>
<color name="login_error">#FFB5472F</color>
<color name="login_error_container">#1AB5472F</color>
<color name="login_mark_ink">#FF0A0A0A</color>
<color name="black">#000000</color>
<color name="white">#FFFFFF</color>
```

---

## 31. 14 unused color resources in `values-night/colors.xml`

- **Group:** Android resources
- **Original file:** `android/app/src/main/res/values-night/colors.xml`
- **Why it is dead:** Lint reports them unused: left over from the old XML login/dashboard screens.

```xml
<color name="login_background">#FF000000</color>
<color name="login_surface">#FF0E0E0E</color>
<color name="login_ink">#FFF4F4F0</color>
<color name="login_ink_soft">#FF8A8A85</color>
<color name="login_field">#FF171717</color>
<color name="login_hairline">#FF262626</color>
<color name="login_brand">#FFF4F4F0</color>
<color name="login_brand_disabled">#80F4F4F0</color>
<color name="login_on_brand">#FF000000</color>
<color name="login_hero_top">#FF1C1C1C</color>
<color name="login_hero_bottom">#FF000000</color>
<color name="login_error">#FFE28A76</color>
<color name="login_error_container">#1FE28A76</color>
<color name="login_mark_ink">#FF0A0A0A</color>
```

---

## 32. 18 unused string resources in `values/strings.xml`

- **Group:** Android resources
- **Original file:** `android/app/src/main/res/values/strings.xml`
- **Why it is dead:** Lint reports them unused: left over from the old XML login/dashboard screens.

```xml
<string name="brand_subtitle">Smart Expense Tracker</string>
<string name="login_title">Sign In</string>
<string name="login_subtitle">Enter your credentials to continue</string>
<string name="hint_email">Email Address</string>
<string name="hint_password">Password</string>
<string name="btn_login">Sign In</string>
<string name="btn_logging_in">Signing In…</string>
<string name="btn_test_sms">Send Test SMS</string>
<string name="btn_logout">Logout</string>
<string name="label_connection_status">Connection Status</string>
<string name="status_connected">Connected to Backend</string>
<string name="status_disconnected">Not Connected</string>
<string name="label_total_synced">Total Transactions Synced</string>
<string name="label_last_sms">Last SMS Received</string>
<string name="label_no_sms">No SMS received yet</string>
<string name="label_logged_in_as">Logged in as</string>
<string name="dashboard_title">Dashboard</string>
<string name="dashboard_subtitle">SMS auto-sync is active in the background</string>
```

---

## 33. Drawable `ic_smartspend_logo.xml`

- **Group:** Android resources
- **Original file:** `android/app/src/main/res/drawable/ic_smartspend_logo.xml` (was around lines 1-18)
- **Why it is dead:** Lint reports it unused.

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Landmark/Bank icon matching the web app branding -->
    <path
        android:fillColor="#FF6366F1"
        android:pathData="M12,2L2,7v1h20V7L12,2z" />
    <path
        android:fillColor="#FF818CF8"
        android:pathData="M4,10v7h3v-7H4z M10,10v7h4v-7h-4z M17,10v7h3v-7h-3z" />
    <path
        android:fillColor="#FF6366F1"
        android:pathData="M2,19v2h20v-2H2z" />
</vector>
```

---

## 34. Drawable `login_field_bg.xml`

- **Group:** Android resources
- **Original file:** `android/app/src/main/res/drawable/login_field_bg.xml` (was around lines 1-19)
- **Why it is dead:** Lint reports it unused.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Filled field; a brand-coloured ring shows which one has focus. -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_focused="true">
        <shape>
            <solid android:color="@color/login_field" />
            <stroke android:width="1.5dp" android:color="@color/login_brand" />
            <corners android:radius="14dp" />
        </shape>
    </item>
    <item>
        <shape>
            <solid android:color="@color/login_field" />
            <stroke android:width="1.5dp" android:color="@android:color/transparent" />
            <corners android:radius="14dp" />
        </shape>
    </item>
</selector>
```
