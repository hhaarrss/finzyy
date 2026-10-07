package com.smartspend.app

data class CategorySummaryItem(
    val category: String,
    val total: Double,
    val percentage: Double,
    val transaction_count: Int,
    val top_merchant: String,
    val budget_limit: Double,
    val budget_used_percent: Double
)

data class MonthlyCategorySummaryResponse(
    val month: Int,
    val year: Int,
    val total_spent: Double,
    val categories: List<CategorySummaryItem>,
    val previous_month_total: Double,
    val month_over_month_change: Double
)

data class PaginatedTransactionResponse(
    val transactions: List<TransactionData>,
    val total_count: Int,
    val page: Int,
    val limit: Int,
    val has_more: Boolean,
    val total_pages: Int
)

data class CategoryListsResponse(
    val debit: List<String>,
    val credit: List<String>
)

data class BudgetUtilizationData(
    val category: String,
    val spent: Double,
    val limit: Double,
    val percent_used: Double,
    val is_alert: Boolean,
    val is_family_limit: Boolean
)

data class OverallBudgetData(
    val id: Int,
    val user_id: Int,
    val monthly_limit: Double
)

data class OverallBudgetPayload(
    val monthly_limit: Double
)

data class HomeUserData(
    val id: Int,
    val full_name: String?,
    val email: String?
)

data class HomeOverviewData(
    val total_spent: Double,
    val total_income: Double,
    val net_savings: Double,
    val mom_change_percent: Double,
    val needs_review_count: Int
)

data class HomeRecentTransactionData(
    val id: Int,
    val amount: Double,
    val type: String,
    val category: String,
    val merchant: String?,
    /** The merchant text exactly as the bank sent it; `merchant` may be a cleaned brand name. */
    val merchant_raw: String? = null,
    val date: String?,
    val review_status: String?
)

data class HomeCategoryData(
    val category: String,
    val spent: Double
)

data class HomeBudgetSnapshotData(
    val category: String,
    val spent: Double,
    val limit: Double,
    val percent_used: Double,
    val is_alert: Boolean
)

data class HomeData(
    val user: HomeUserData,
    val month: Int,
    val year: Int,
    val overview: HomeOverviewData,
    val recent_transactions: List<HomeRecentTransactionData>,
    val top_categories: List<HomeCategoryData>,
    val budget_snapshot: List<HomeBudgetSnapshotData>
)
