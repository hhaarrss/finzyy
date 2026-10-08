package com.smartspend.app

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

class TransactionListDeserializer : JsonDeserializer<PaginatedTransactionResponse> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): PaginatedTransactionResponse {
        if (json == null || json.isJsonNull) {
            return PaginatedTransactionResponse(emptyList(), 0, 1, 50, false, 0)
        }
        if (json.isJsonArray) {
            val listType = object : TypeToken<List<TransactionData>>() {}.type
            val items: List<TransactionData> = context?.deserialize(json, listType) ?: emptyList()
            return PaginatedTransactionResponse(items, items.size, 1, items.size, false, 1)
        } else if (json.isJsonObject) {
            val obj = json.asJsonObject
            val listType = object : TypeToken<List<TransactionData>>() {}.type
            val itemsElement = obj.get("transactions")
            val items: List<TransactionData> = if (itemsElement != null && !itemsElement.isJsonNull) {
                context?.deserialize(itemsElement, listType) ?: emptyList()
            } else {
                emptyList()
            }
            val totalCount = obj.get("total_count")?.asInt ?: items.size
            val page = obj.get("page")?.asInt ?: 1
            val limit = obj.get("limit")?.asInt ?: (if (items.isNotEmpty()) items.size else 50)
            val hasMore = obj.get("has_more")?.asBoolean ?: false
            val totalPages = obj.get("total_pages")?.asInt ?: 1
            return PaginatedTransactionResponse(items, totalCount, page, limit, hasMore, totalPages)
        }
        return PaginatedTransactionResponse(emptyList(), 0, 1, 50, false, 0)
    }
}

/**
 * Payload for SMS ingestion endpoint.
 */
data class SmsPayload(
    val amount: Double,
    val transaction_type: String,
    val merchant_raw: String?,
    val bank_sender_id: String?,
    val account_last4: String?,
    val date: String,
    val upi_ref: String? = null
)

/**
 * Response from every sign-in endpoint (/auth/firebase, /auth/login, /auth/register):
 * the session token plus where the app should go next.
 */
data class AuthResponse(
    val access_token: String,
    val token_type: String,
    val is_new_user: Boolean = false,
    val profile_complete: Boolean = false,
    val phone_number: String? = null,
    val email: String? = null,
    val full_name: String? = null
)

/** A Firebase ID token from Phone (OTP) or Google sign-in, verified by the backend. */
data class FirebaseTokenPayload(val id_token: String)

/** Details sent from the profile setup / edit screen. Dates are ISO yyyy-MM-dd. */
data class ProfilePayload(
    val full_name: String,
    val email: String? = null,
    val date_of_birth: String? = null,
    val gender: String? = null,
    val city: String? = null,
    val occupation: String? = null,
    val monthly_income: Double? = null,
    val monthly_budget: Double? = null
)

/**
 * Data model for a User profile.
 */
data class UserData(
    val id: Int,
    val email: String? = null,
    val phone_number: String? = null,
    val full_name: String? = null,
    val date_of_birth: String? = null,
    val gender: String? = null,
    val city: String? = null,
    val occupation: String? = null,
    val monthly_income: Double? = null,
    val monthly_budget: Double? = null,
    val profile_complete: Boolean = false
)

/**
 * Transaction data model returned inside API responses.
 */
data class TransactionData(
    val id: Int,
    val user_id: Int,
    val amount: Double,
    val type: String,
    val category: String,
    val merchant: String?,
    /** The merchant text exactly as the bank sent it; `merchant` may be a cleaned brand name. */
    val merchant_raw: String? = null,
    val subcategory: String?,
    val bank: String?,
    val account_last4: String?,
    val date: String,
    val source: String?,
    val confidence: String?,
    val review_status: String?,
    val is_transfer: Boolean = false,
    val transfer_to: String? = null,
    val created_at: String
)

data class RecategorizePayload(
    val transaction_id: Int,
    val merchant_raw: String,
    val new_category: String,
    val subcategory: String? = null,
    val display_name: String? = null
)

/**
 * Response model for the /transactions/ingest-sms endpoint.
 */
data class SmsIngestionResponse(
    val success: Boolean,
    val transaction: TransactionData?,
    val message: String,
    /** Debits at this merchant including this one; null on older servers or for credits. */
    val merchant_visit_count: Int? = null,
    /** This month's spend, the same figure Home shows; null on older servers. */
    val month_spent: Double? = null
)

/**
 * Data model for Budget Limit returned from /budget/ endpoint.
 */
data class BudgetLimitData(
    val id: Int,
    val user_id: Int,
    val category: String,
    val monthly_limit: Double,
    val alert_at_percent: Double?,
    val is_family_limit: Boolean?
)

/**
 * Payload for setting / updating category budget limit.
 */
data class BudgetSetPayload(
    val category: String,
    val monthly_limit: Double,
    val alert_at_percent: Double = 80.0,
    val is_family_limit: Boolean = false
)

/**
 * Payload for creating manual transaction.
 */
data class TransactionCreatePayload(
    val amount: Double,
    val type: String,
    val category: String,
    val merchant: String?,
    val date: String,
    val notes: String? = null
)

/**
 * Data model for spending change item inside Insights summary.
 */
data class SpendingChangeItem(
    val category: String,
    val change_percent: Double? = null,
    val direction: String? = null,
    val not_enough_data: Boolean = false
)

/**
 * Response model for the /insights/summary endpoint.
 */
data class InsightsSummaryData(
    val spending_changes: List<SpendingChangeItem>?,
    val anomalies: List<Map<String, Any>>?,
    val recurring: List<Map<String, Any>>?,
    val budget_alerts: List<Map<String, Any>>?
)

data class TransactionUpdatePayload(
    val category: String? = null,
    val merchant: String? = null,
    val amount: Double? = null,
    val date: String? = null,
    val notes: String? = null
)

data class FcmTokenPayload(
    val fcm_token: String
)

data class NeedsReviewResponse(
    val count: Int,
    val transactions: List<TransactionData>,
    val message: String
)

data class DeleteAccountPayload(
    val password: String? = null,
    val confirmation_text: String
)

data class DeleteAccountResponse(
    val success: Boolean,
    val message: String,
    val deleted_at: String
)

/**
 * Retrofit service interface for all Finzyy backend API calls.
 */
interface BackendService {

    @HTTP(method = "DELETE", path = "users/me", hasBody = true)
    suspend fun deleteMyAccount(
        @Header("Authorization") token: String,
        @Body payload: DeleteAccountPayload
    ): Response<DeleteAccountResponse>

    @HTTP(method = "DELETE", path = "users/me", hasBody = true)
    suspend fun deleteMyAccountNoAuth(
        @Body payload: DeleteAccountPayload
    ): Response<DeleteAccountResponse>

    /**
     * Fetch consolidated Home screen data. Auth is bypassed by the backend while AUTH_STUB=true.
     */
    @GET("home")
    suspend fun getHomeData(@Header("Authorization") token: String): Response<HomeData>

    /**
     * Fetch transactions requiring user review/categorization.
     */
    @GET("transactions/needs-review")
    suspend fun getNeedsReviewTransactions(
        @Header("Authorization") token: String
    ): Response<NeedsReviewResponse>

    /**
     * Register device FCM push notification token.
     */
    @POST("users/fcm-token")
    suspend fun registerFcmToken(
        @Header("Authorization") token: String,
        @Body payload: FcmTokenPayload
    ): Response<Map<String, String>>

    @GET("categories")
    suspend fun getCategoryLists(): Response<CategoryListsResponse>

    /**
     * Partially edit a transaction (category, merchant, amount, date, notes).
     */
    @PATCH("transactions/{id}")
    suspend fun patchTransaction(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body payload: TransactionUpdatePayload
    ): Response<TransactionData>

    /**
     * Delete a transaction.
     */
    @retrofit2.http.DELETE("transactions/{id}")
    suspend fun deleteTransaction(
        @Header("Authorization") token: String,
        @Path("id") id: Int
    ): Response<Map<String, String>>

    /**
     * Authenticate user and obtain a JWT access token.
     */
    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String, // OAuth2 expects 'username' field for email
        @Field("password") password: String
    ): Response<AuthResponse>

    /** Exchanges a Firebase ID token (Phone OTP or Google) for a Finzyy session. */
    @POST("auth/firebase")
    suspend fun firebaseLogin(@Body payload: FirebaseTokenPayload): Response<AuthResponse>

    /** Adds an OTP-verified phone number to the signed-in account. */
    @POST("auth/link-phone")
    suspend fun linkPhone(@Body payload: FirebaseTokenPayload): Response<UserData>

    @GET("users/me")
    suspend fun getMyProfile(
        @Header("Authorization") token: String = ""
    ): Response<UserData>

    @retrofit2.http.PUT("users/me/profile")
    suspend fun updateProfile(@Body payload: ProfilePayload): Response<UserData>

    /**
     * Send parsed SMS data to the backend for transaction ingestion.
     */
    @POST("transactions/ingest-sms")
    suspend fun ingestSms(
        @Header("Authorization") token: String,
        @Body payload: SmsPayload
    ): Response<SmsIngestionResponse>

    @GET("transactions/")
    suspend fun getTransactionsNoAuth(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("month") month: Int? = null,
        @Query("year") year: Int? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("category") category: String? = null,
        @Query("type") type: String? = null,
        @Query("include_transfers") includeTransfers: Boolean? = null
    ): Response<PaginatedTransactionResponse>

    @GET("transactions/monthly-category-summary")
    suspend fun getMonthlyCategorySummaryNoAuth(
        @Query("month") month: Int,
        @Query("year") year: Int
    ): Response<MonthlyCategorySummaryResponse>

    @GET("budget/utilization")
    suspend fun getBudgetUtilization(): Response<List<BudgetUtilizationData>>

    @GET("budget/overall")
    suspend fun getOverallBudget(): Response<OverallBudgetData?>

    @POST("budget/overall")
    suspend fun setOverallBudget(
        @Body payload: OverallBudgetPayload
    ): Response<OverallBudgetData>

    @POST("budget/")
    suspend fun setBudgetNoAuth(
        @Body payload: BudgetSetPayload
    ): Response<BudgetLimitData>

    @POST("transactions/")
    suspend fun createTransactionNoAuth(
        @Body payload: TransactionCreatePayload
    ): Response<TransactionData>

    /**
     * Re-categorize a transaction (updates category, review status, and saves user learning).
     */
    @PATCH("transactions/{id}/recategorize")
    suspend fun recategorizeTransaction(
        @Header("Authorization") token: String,
        @Path("id") id: Int,
        @Body payload: RecategorizePayload
    ): Response<Map<String, Any>>

    @GET("insights/summary")
    suspend fun getInsightsSummaryNoAuth(): Response<InsightsSummaryData>

    companion object {
        private val BASE_URL =
            // expense-tracker-pk4d.onrender.com is the retired Render service — it accepts the
            // connection and never answers, so every call hit the 60s timeout.
            if (BuildConfig.DEV_SKIP_AUTH) BuildConfig.DEV_BACKEND_BASE_URL else "https://firstproject-smartspend.onrender.com/"

        /**
         * Creates a configured Retrofit BackendService instance with resilient timeouts.
         */
        fun create(): BackendService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(AuthHeaderInterceptor())
                .build()

            val gson = GsonBuilder()
                .registerTypeAdapter(PaginatedTransactionResponse::class.java, TransactionListDeserializer())
                .create()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(BackendService::class.java)
        }
    }
}
