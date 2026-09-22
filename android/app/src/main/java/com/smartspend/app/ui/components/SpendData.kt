package com.smartspend.app.ui.components

import com.smartspend.app.CategoryListsResponse
import com.smartspend.app.MonthlyCategorySummaryResponse
import com.smartspend.app.RetrofitClient
import com.smartspend.app.TransactionData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

/**
 * Thin read helpers over existing endpoints — no new aggregation lives here. Anything that
 * is also shown on another screen (category totals, budget use) comes from the backend's
 * aggregate endpoints; only per-merchant grouping and chart bucketing are done client-side,
 * because no endpoint exposes those for an arbitrary date range.
 */
object SpendData {

    /** Every transaction in [start]..[end], walking the paginated list endpoint. */
    suspend fun transactions(
        start: LocalDate,
        end: LocalDate,
        type: String? = null,
        category: String? = null,
        includeTransfers: Boolean? = null,
        maxPages: Int = 20
    ): List<TransactionData> {
        val all = mutableListOf<TransactionData>()
        var page = 1
        while (page <= maxPages) {
            val response = RetrofitClient.apiService.getTransactionsNoAuth(
                page = page,
                limit = 50,
                startDate = start.toString(),
                endDate = end.toString(),
                category = category,
                type = type,
                includeTransfers = includeTransfers
            )
            if (!response.isSuccessful) throw IOException("Transactions failed to load (${response.code()})")
            val body = response.body() ?: break
            all.addAll(body.transactions)
            if (!body.has_more) break
            page++
        }
        return all
    }

    suspend fun monthSummary(month: YearMonth): MonthlyCategorySummaryResponse? {
        val response = RetrofitClient.apiService.getMonthlyCategorySummaryNoAuth(month.monthValue, month.year)
        return if (response.isSuccessful) response.body() else null
    }

    /** Summaries for several months, fetched in parallel, oldest first. */
    suspend fun monthSummaries(months: List<YearMonth>): List<Pair<YearMonth, MonthlyCategorySummaryResponse?>> =
        coroutineScope {
            months.map { m -> async { m to runCatching { monthSummary(m) }.getOrNull() } }.awaitAll()
        }

    suspend fun categoryLists(): CategoryListsResponse {
        val response = RetrofitClient.apiService.getCategoryLists()
        return response.body()?.takeIf { response.isSuccessful }
            ?: throw IOException("Categories failed to load (${response.code()})")
    }
}

/** Spending categories users pick from, minus the placeholders that are not real choices. */
fun List<String>.pickable(): List<String> =
    filterNot { it.equals("Needs Review", ignoreCase = true) || it.equals("Miscellaneous", ignoreCase = true) }
