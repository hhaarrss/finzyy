package com.smartspend.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartspend.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.provider.Telephony
import android.widget.NumberPicker
import androidx.activity.compose.setContent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.smartspend.app.ui.navigation.SmartSpendNavHost
import com.smartspend.app.ui.theme.SmartSpendTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Main entry point for the SmartSpend 3-Screen Android Companion App.
 *
 * Screens:
 * - Screen 1 (Home): Summary Cards, Daily Spend Chart, Donut Chart, Search & Recent Transactions.
 * - Screen 2 (Add Transaction): Manual debit/credit entry form & AI SMS auto-ingest box.
 * - Screen 3 (Budget Limits): Category budget progress cards, warning header, edit limits.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var transactionAdapter: TransactionAdapter
    private lateinit var budgetAdapter: BudgetAdapter
    private lateinit var categoryAdapter: CategoryAdapter

    private var allTransactions: List<TransactionData> = emptyList()
    private var allBudgets: List<BudgetLimitData> = emptyList()
    private var categorySpendingMap: Map<String, Double> = emptyMap()
    private var categorySummaryItems: List<CategorySummaryItem> = emptyList()

    private var currentFilterMode = "ALL" // ALL, NEEDS_REVIEW, DEBITS, CREDITS
    private var isDebitType = true

    private val nowCal = Calendar.getInstance()
    private var selectedMonth: Int = nowCal.get(Calendar.MONTH) + 1
    private var selectedYear: Int = nowCal.get(Calendar.YEAR)
    private var visibleTxCount = PAGE_SIZE
    private var remainingTxCount = 0
    private var hasScrolledAwayFromTop = false

    private var isRegisterMode = false

    private val canonicalCategories = listOf(
        "Food", "Transport", "Shopping", "Entertainment", "Utilities",
        "Healthcare", "Education", "Travel", "Rent", "Transfer",
        "Investment", "Salary", "Refund", "Other"
    )

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "jwt_token" || key == "total_synced" || key == "last_sms") {
            updateSyncHubStats()
            fetchDashboardData()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "SMS Permissions Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "SMS Permissions Denied", Toast.LENGTH_SHORT).show()
        }
    }

    /** Handles result from Google Sign-In intent. */
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (!idToken.isNullOrEmpty()) {
                firebaseAuthWithGoogle(idToken)
            } else {
                val email = account.email ?: "google_user@smartspend.app"
                val displayName = account.displayName ?: email.substringBefore("@")
                sharedPrefs.edit()
                    .putString("jwt_token", "google_${account.id ?: email.hashCode()}")
                    .putString("user_email", email)
                    .apply()
                Toast.makeText(this, "Welcome, $displayName! 👋", Toast.LENGTH_SHORT).show()
                showDashboard()
            }
        } catch (e: ApiException) {
            val errorMsg = when (e.statusCode) {
                10 -> "Google Sign-In configuration error (Code 10: SHA-1 fingerprint needs to be added in Firebase Console)."
                12500 -> "Sign in failed (Code 12500: Google Play Services issue or unlinked OAuth client)."
                12501 -> "Sign-in was cancelled."
                else -> "Google Sign-In failed (Code ${e.statusCode}): ${e.localizedMessage ?: "Unknown error"}"
            }
            if (e.statusCode == 10) {
                AlertDialog.Builder(this)
                    .setTitle("Google Sign-In Setup Required")
                    .setMessage("Error 10 indicates that your Android app's debug SHA-1 fingerprint has not been registered in the Firebase Console yet.\n\nPlease add your SHA-1 to Firebase Project Settings -> Android App.")
                    .setPositiveButton("OK", null)
                    .show()
            } else if (e.statusCode != 12501) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keeps bank SMS syncing (and retrying) in the background while the app is closed.
        SmsSyncWorker.schedulePeriodic(this)

        if (BuildConfig.DEV_SKIP_AUTH) {
            // The dev-stub backend (AUTH_STUB=true) ignores the token's contents and always
            // resolves to the seeded stub user, but every client call — including SmsReceiver's
            // background ingest — still checks for a non-empty token locally before it will even
            // attempt the request. Without this, real incoming SMS are parsed but never synced.
            val devPrefs = getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
            if (devPrefs.getString("jwt_token", null).isNullOrEmpty()) {
                devPrefs.edit()
                    .putString("jwt_token", "dev-stub-token")
                    .putString("user_email", "dev@smartspend.app")
                    .apply()
            }
            setContent {
                SmartSpendTheme {
                    SmartSpendNavHost()
                }
            }
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        val webClientId = getString(R.string.default_web_client_id)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        if (webClientId.isNotBlank() && !webClientId.contains("placeholder")) {
            gsoBuilder.requestIdToken(webClientId)
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        sharedPrefs = getSharedPreferences("smart_spend_prefs", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)

        setupAdapters()
        setupBottomNavigation()
        setupFormControls()
        setupMonthPicker()
        setupTransactionPagination()
        checkPermissions()
        setupListeners()
        navigateToCorrectScreen()
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEV_SKIP_AUTH) return
        val token = sharedPrefs.getString("jwt_token", null)
        if (!token.isNullOrEmpty()) {
            lifecycleScope.launch {
                SmsReceiver.flushOfflineQueue(this@MainActivity, token)
                fetchDashboardData()
            }
            registerFcmTokenIfAvailable(token)
        }
    }

    private fun registerFcmTokenIfAvailable(jwtToken: String) {
        val fcmToken = sharedPrefs.getString("fcm_token", null)
        if (!fcmToken.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.apiService.registerFcmToken("Bearer $jwtToken", FcmTokenPayload(fcmToken))
                } catch (e: Exception) {
                    // Suppress
                }
            }
        }
    }

    private fun setupAdapters() {
        transactionAdapter = TransactionAdapter { tx ->
            showReviewTransactionDialog(tx)
        }
        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.rvTransactions.adapter = transactionAdapter
        binding.rvTransactions.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 280
            removeDuration = 280
            moveDuration = 280
        }

        budgetAdapter = BudgetAdapter { budget ->
            showEditBudgetDialog(budget)
        }
        binding.rvBudgetLimits.layoutManager = LinearLayoutManager(this)
        binding.rvBudgetLimits.adapter = budgetAdapter
    }

    // ──────────────── Bottom Navigation ────────────────

    private fun setupBottomNavigation() {
        binding.navHome.setOnClickListener { switchScreen(0) }
        binding.navAdd.setOnClickListener { switchScreen(1) }
        binding.navBudget.setOnClickListener { switchScreen(2) }
        binding.navInsights.setOnClickListener { switchScreen(3) }
        binding.navProfile.setOnClickListener { switchScreen(4) }
        binding.btnProfileHeader.setOnClickListener { switchScreen(4) }
        binding.btnRefreshInsights.setOnClickListener { fetchInsightsData() }
        binding.btnLogoutProfile.setOnClickListener { showLogoutConfirmationDialog() }
        binding.btnDeleteAccountProfile.setOnClickListener { showDeleteAccountWarningDialog() }
        binding.btnPrivacyPolicyProfile.setOnClickListener { showPrivacyPolicyDialog() }
    }

    private fun switchScreen(screenIndex: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.emerald_success)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        binding.tvNavHome.setTextColor(if (screenIndex == 0) activeColor else inactiveColor)
        binding.tvNavAdd.setTextColor(if (screenIndex == 1) activeColor else inactiveColor)
        binding.tvNavBudget.setTextColor(if (screenIndex == 2) activeColor else inactiveColor)
        binding.tvNavInsights.setTextColor(if (screenIndex == 3) activeColor else inactiveColor)
        binding.tvNavProfile.setTextColor(if (screenIndex == 4) activeColor else inactiveColor)

        binding.screenHome.visibility = if (screenIndex == 0) View.VISIBLE else View.GONE
        binding.screenAddTransaction.visibility = if (screenIndex == 1) View.VISIBLE else View.GONE
        binding.screenBudget.visibility = if (screenIndex == 2) View.VISIBLE else View.GONE
        binding.screenInsights.visibility = if (screenIndex == 3) View.VISIBLE else View.GONE
        binding.screenProfile.visibility = if (screenIndex == 4) View.VISIBLE else View.GONE

        if (screenIndex == 0 || screenIndex == 2) {
            fetchDashboardData()
        } else if (screenIndex == 3) {
            fetchInsightsData()
        } else if (screenIndex == 4) {
            renderProfileDetails()
        }
    }

    private fun navigateToCorrectScreen() {
        val token = sharedPrefs.getString("jwt_token", null)
        if (token.isNullOrEmpty()) {
            showLogin()
        } else {
            showDashboard()
        }
    }

    private fun showLogin() {
        binding.loginSection.visibility = View.VISIBLE
        binding.dashboardSection.visibility = View.GONE
    }

    private fun showDashboard() {
        binding.loginSection.visibility = View.GONE
        binding.dashboardSection.visibility = View.VISIBLE
        updateSyncHubStats()
        switchScreen(0) // Default to Home
    }

    private fun handleUnauthorized() {
        runOnUiThread {
            sharedPrefs.edit().remove("jwt_token").apply()
            Toast.makeText(this, "Session expired or invalid login. Please log in again.", Toast.LENGTH_LONG).show()
            showLogin()
        }
    }

    private fun setupFormControls() {
        // Category Spinner setup
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, canonicalCategories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spManualCategory.adapter = adapter

        // Search text listener
        binding.etSearchTransactions.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearchAndFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter pills listeners
        binding.btnFilterAll.setOnClickListener { setFilterMode("ALL") }
        binding.btnFilterNeedsReview.setOnClickListener { setFilterMode("NEEDS_REVIEW") }
        binding.btnFilterDebits.setOnClickListener { setFilterMode("DEBITS") }
        binding.btnFilterCredits.setOnClickListener { setFilterMode("CREDITS") }

        // Manual Entry Type Toggle
        binding.btnToggleDebit.setOnClickListener {
            isDebitType = true
            binding.btnToggleDebit.setBackgroundResource(R.drawable.bg_gradient_button)
            binding.btnToggleCredit.setBackgroundResource(R.drawable.bg_input_dark)
        }
        binding.btnToggleCredit.setOnClickListener {
            isDebitType = false
            binding.btnToggleCredit.setBackgroundResource(R.drawable.bg_gradient_button)
            binding.btnToggleDebit.setBackgroundResource(R.drawable.bg_input_dark)
        }

        binding.btnSaveManualTransaction.setOnClickListener { performSaveTransaction() }
        binding.btnParseAndIngestSms.setOnClickListener { performParseSms() }
        binding.btnSyncHistoricalSms.setOnClickListener { syncHistoricalSms() }
        binding.btnRefreshFeed.setOnClickListener {
            Toast.makeText(this, "Syncing latest data...", Toast.LENGTH_SHORT).show()
            fetchDashboardData()
        }
        binding.btnExportCsv.setOnClickListener {
            Toast.makeText(this, "Exporting ${allTransactions.size} transactions to CSV...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMonthPicker() {
        refreshMonthLabel()
        binding.tvSelectedMonth.setOnClickListener { showMonthPickerDialog() }
    }

    private fun setupTransactionPagination() {
        binding.btnShowMore.setOnClickListener {
            visibleTxCount += PAGE_SIZE
            applySearchAndFilter()
        }

        binding.rvTransactions.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) hasScrolledAwayFromTop = true
                if (dy < 0 && !recyclerView.canScrollVertically(-1)) {
                    collapseTransactionsIfNeeded()
                }
            }
        })

        binding.screenHome.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY + 8) hasScrolledAwayFromTop = true
            if (scrollY < oldScrollY && scrollY <= 24) {
                collapseTransactionsIfNeeded()
            }
        }
    }

    private fun collapseTransactionsIfNeeded() {
        if (hasScrolledAwayFromTop && visibleTxCount > PAGE_SIZE) {
            visibleTxCount = PAGE_SIZE
            hasScrolledAwayFromTop = false
            applySearchAndFilter()
        }
    }

    private fun shiftMonth(delta: Int) {
        var month = selectedMonth + delta
        var year = selectedYear
        if (month < 1) {
            month = 12
            year -= 1
        } else if (month > 12) {
            month = 1
            year += 1
        }
        if (year < 2020 || year > 2030) return
        selectedMonth = month
        selectedYear = year
        visibleTxCount = PAGE_SIZE
        hasScrolledAwayFromTop = false
        refreshMonthLabel()
        fetchDashboardData()
    }

    private fun refreshMonthLabel() {
        val label = DateUtils.formatMonthYear(selectedMonth, selectedYear)
        binding.tvSelectedMonth.text = "📅 $label"
        binding.tvDailySpendingHeader.text = "Daily Spending — $label"
    }

    private fun showMonthPickerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_month_picker, null)
        val npMonth = view.findViewById<NumberPicker>(R.id.npMonth)
        val npYear = view.findViewById<NumberPicker>(R.id.npYear)
        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        npMonth.minValue = 0
        npMonth.maxValue = 11
        npMonth.displayedValues = months
        npMonth.value = selectedMonth - 1
        npMonth.wrapSelectorWheel = false

        npYear.minValue = 2020
        npYear.maxValue = 2030
        npYear.value = selectedYear
        npYear.wrapSelectorWheel = false

        AlertDialog.Builder(this)
            .setTitle("Select month")
            .setView(view)
            .setPositiveButton("Apply") { _, _ ->
                selectedMonth = npMonth.value + 1
                selectedYear = npYear.value
                visibleTxCount = PAGE_SIZE
                hasScrolledAwayFromTop = false
                refreshMonthLabel()
                fetchDashboardData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener { performLogin() }
        binding.btnGoogleSignIn.setOnClickListener { launchGoogleSignIn() }
        binding.btnToggleAuthMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            if (isRegisterMode) {
                binding.tvLoginTitle.text = "Create Account"
                binding.tvLoginSubtitle.text = "Join SmartSpend to track your expenses"
                binding.llFullName.visibility = View.VISIBLE
                binding.btnLogin.text = "Sign Up"
                binding.btnToggleAuthMode.text = "Already have an account? Sign In"
            } else {
                binding.tvLoginTitle.text = getString(R.string.login_title)
                binding.tvLoginSubtitle.text = "Sign in to pair your Android device with your Expense Dashboard"
                binding.llFullName.visibility = View.GONE
                binding.btnLogin.text = getString(R.string.btn_login)
                binding.btnToggleAuthMode.text = "Don't have an account? Sign up"
            }
        }
    }

    private fun setFilterMode(mode: String) {
        currentFilterMode = mode
        val activeBg = R.drawable.bg_gradient_button
        val inactiveBg = R.drawable.bg_input_dark

        binding.btnFilterAll.setBackgroundResource(if (mode == "ALL") activeBg else inactiveBg)
        binding.btnFilterNeedsReview.setBackgroundResource(if (mode == "NEEDS_REVIEW") activeBg else inactiveBg)
        binding.btnFilterDebits.setBackgroundResource(if (mode == "DEBITS") activeBg else inactiveBg)
        binding.btnFilterCredits.setBackgroundResource(if (mode == "CREDITS") activeBg else inactiveBg)

        applySearchAndFilter()
    }

    private fun applySearchAndFilter() {
        val query = binding.etSearchTransactions.text.toString().trim().lowercase()

        val filtered = DateUtils.sortLatestFirst(allTransactions.filter { tx ->
            val matchesQuery = query.isEmpty() ||
                    (tx.merchant?.lowercase()?.contains(query) == true) ||
                    (tx.category.lowercase().contains(query)) ||
                    (tx.bank?.lowercase()?.contains(query) == true)

            val matchesFilter = when (currentFilterMode) {
                "NEEDS_REVIEW" -> tx.review_status?.contains("needs_review", ignoreCase = true) == true
                "DEBITS" -> tx.type.equals("debit", ignoreCase = true)
                "CREDITS" -> tx.type.equals("credit", ignoreCase = true)
                else -> true
            }

            matchesQuery && matchesFilter
        })

        remainingTxCount = (filtered.size - visibleTxCount).coerceAtLeast(0)
        val visible = filtered.take(visibleTxCount)
        transactionAdapter.submitList(buildTxListItems(visible))

        if (remainingTxCount > 0) {
            binding.btnShowMore.visibility = View.VISIBLE
            binding.btnShowMore.text = "Show More ($remainingTxCount remaining)"
        } else {
            binding.btnShowMore.visibility = View.GONE
        }
    }

    private fun buildTxListItems(transactions: List<TransactionData>): List<TxListItem> {
        val items = mutableListOf<TxListItem>()
        var lastHeader: String? = null
        for (tx in transactions) {
            val header = DateUtils.dayHeader(tx)
            if (header != lastHeader) {
                items.add(TxListItem.Header(header))
                lastHeader = header
            }
            items.add(TxListItem.Row(tx))
        }
        return items
    }

    // ──────────────── Data Fetching & Dashboard Overview Binding ────────────────

    private fun fetchDashboardData() {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        if (token.isEmpty()) {
            showLogin()
            return
        }
        val authHeader = "Bearer $token"
        refreshMonthLabel()
        binding.chartLoadingSpinner.visibility = View.VISIBLE
        binding.cvDailySpendChart.alpha = 0.35f

        lifecycleScope.launch {
            try {
                val txs = fetchAllMonthTransactions(authHeader, selectedMonth, selectedYear)
                if (txs == null) {
                    runOnUiThread {
                        binding.chartLoadingSpinner.visibility = View.GONE
                        binding.cvDailySpendChart.alpha = 1f
                    }
                    return@launch
                }
                allTransactions = DateUtils.sortLatestFirst(txs)

                val catResp = RetrofitClient.apiService.getMonthlyCategorySummary(
                    authHeader, selectedMonth, selectedYear
                )
                if (catResp.code() == 401) {
                    handleUnauthorized()
                    return@launch
                }
                val monthlySummary = if (catResp.isSuccessful) catResp.body() else null
                if (monthlySummary != null) {
                    categorySpendingMap = monthlySummary.categories.associate { it.category to it.total }
                    categorySummaryItems = monthlySummary.categories.sortedByDescending { it.total }
                } else {
                    val fallbackMonth = "%04d-%02d".format(selectedYear, selectedMonth)
                    val oldCat = RetrofitClient.apiService.getCategorySummary(authHeader, fallbackMonth)
                    if (oldCat.isSuccessful && oldCat.body() != null) {
                        categorySpendingMap = oldCat.body()!!
                    }
                    categorySummaryItems = categorySpendingMap.map { (name, total) ->
                        CategorySummaryItem(name, total, 0.0, 0, "N/A", 0.0, 0.0)
                    }.sortedByDescending { it.total }
                }

                val bResp = RetrofitClient.apiService.getBudgets(authHeader)
                if (bResp.isSuccessful && bResp.body() != null) {
                    allBudgets = bResp.body()!!
                }

                // Provide Default Budget Limits if Database is Empty
                if (allBudgets.isEmpty()) {
                    allBudgets = listOf(
                        BudgetLimitData(1, 1, "Shopping", 5000.0, 80.0, false),
                        BudgetLimitData(2, 1, "Food & Dining", 5000.0, 80.0, false),
                        BudgetLimitData(3, 1, "Groceries", 4000.0, 80.0, false),
                        BudgetLimitData(4, 1, "Transportation", 3000.0, 80.0, false),
                        BudgetLimitData(5, 1, "Utilities & Bills", 3500.0, 80.0, false),
                        BudgetLimitData(6, 1, "Entertainment", 2000.0, 80.0, false)
                    )
                }

                runOnUiThread {
                    applySearchAndFilter()
                    budgetAdapter.updateData(allBudgets, categorySpendingMap)
                    renderSummaryStatCards()
                    renderDonutChartAndLegend()
                    updateBudgetWarningHeader()
                }
                checkNeedsReviewBanner(authHeader)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Refresh Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                runOnUiThread {
                    binding.chartLoadingSpinner.visibility = View.GONE
                    binding.cvDailySpendChart.alpha = 1f
                }
            }
        }
    }

    private suspend fun fetchAllMonthTransactions(
        authHeader: String,
        month: Int,
        year: Int
    ): List<TransactionData>? {
        val all = mutableListOf<TransactionData>()
        var page = 1
        while (page <= 40) {
            val txResp = RetrofitClient.apiService.getTransactions(
                authHeader,
                page = page,
                limit = 50,
                month = month,
                year = year
            )
            if (txResp.code() == 401) {
                handleUnauthorized()
                return null
            }
            val body = txResp.body()
            if (!txResp.isSuccessful || body == null) break
            all.addAll(body.transactions)
            if (!body.has_more) break
            page++
        }
        return all
    }

    private fun renderSummaryStatCards() {
        val debits = allTransactions.filter { it.type.equals("debit", ignoreCase = true) }
        val credits = allTransactions.filter { it.type.equals("credit", ignoreCase = true) }

        val merchantSpent = debits.filter { !it.is_transfer && !it.category.equals("Transfer", ignoreCase = true) }.sumOf { it.amount }
        val transferSent = debits.filter { it.is_transfer || it.category.equals("Transfer", ignoreCase = true) }.sumOf { it.amount }
        val totalSpent = merchantSpent + transferSent
        val totalIncome = credits.sumOf { it.amount }
        val netCashFlow = totalIncome - totalSpent

        binding.tvSpentThisMonth.text = "₹%.2f".format(totalSpent)
        binding.tvIncomeCredits.text = "₹%.2f".format(totalIncome)
        binding.tvNetCashFlow.text = "₹%.2f".format(netCashFlow)

        if (netCashFlow >= 0) {
            binding.tvNetCashFlow.setTextColor(ContextCompat.getColor(this, R.color.emerald_success))
        } else {
            binding.tvNetCashFlow.setTextColor(Color.parseColor("#EF4444"))
        }

        // Top Category calculation
        val topCategoryEntry = categorySpendingMap.maxByOrNull { it.value }
        if (topCategoryEntry != null) {
            val totalCatSpend = categorySpendingMap.values.sum().coerceAtLeast(1.0)
            val pct = (topCategoryEntry.value / totalCatSpend) * 100
            binding.tvTopCategoryName.text = topCategoryEntry.key
            binding.tvTopCategoryPercent.text = "%.1f%% of spend".format(pct)
        } else {
            binding.tvTopCategoryName.text = "None"
            binding.tvTopCategoryPercent.text = "0.0% of spend"
        }

        // Daily Spend Bar Chart binding with Today calculation
        val dailyTotals = mutableMapOf<Int, Double>()
        for (tx in debits) {
            try {
                val day = tx.date.substring(8, 10).toInt()
                dailyTotals[day] = (dailyTotals[day] ?: 0.0) + tx.amount
            } catch (e: Exception) {}
        }

        val cal = java.util.Calendar.getInstance()
        val curMonth = cal.get(java.util.Calendar.MONTH) + 1
        val curYear = cal.get(java.util.Calendar.YEAR)
        val curDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val isCurrentMonth = (selectedMonth == curMonth && selectedYear == curYear)

        val avgDailySpend = if (dailyTotals.isNotEmpty()) dailyTotals.values.average() else 0.0
        val daysInMonth = DateUtils.daysInMonth(selectedMonth, selectedYear)
        val barList = mutableListOf<DailyBarData>()
        for (d in 1..daysInMonth) {
            val amt = dailyTotals[d] ?: 0.0
            val isSpike = avgDailySpend > 0 && amt >= (avgDailySpend * 2.0)
            val isToday = isCurrentMonth && (d == curDay)
            barList.add(DailyBarData(day = d, amount = amt, isSpike = isSpike, isToday = isToday))
        }
        binding.cvDailySpendChart.setData(barList)
        binding.cvDailySpendChart.onBarSelectedListener = { bar ->
            Toast.makeText(this, "Day ${bar.day}: Spent ₹%.2f".format(bar.amount), Toast.LENGTH_SHORT).show()
        }

        // Exceeded Banner Card check
        var exceededCat: String? = null
        var exceededSpent = 0.0
        var exceededLimit = 0.0
        var exceededPct = 0

        for (b in allBudgets) {
            val spent = categorySpendingMap[b.category] ?: 0.0
            val limit = b.monthly_limit
            val pct = if (limit > 0) ((spent / limit) * 100).toInt() else 0

            if (pct >= 100 && (spent > exceededSpent)) {
                exceededCat = b.category
                exceededSpent = spent
                exceededLimit = limit
                exceededPct = pct
            }
        }

        if (exceededCat != null) {
            binding.cardBudgetExceededBanner.visibility = View.VISIBLE
            binding.tvExceededBannerTitle.text = "$exceededCat budget exceeded"
            binding.tvExceededBannerSubtitle.text = "₹%.2f of ₹%.2f (%d%% used)".format(exceededSpent, exceededLimit, exceededPct)
        } else {
            binding.cardBudgetExceededBanner.visibility = View.GONE
        }
    }

    private fun formatShortAmount(amount: Double): String {
        return when {
            amount >= 100_000 -> "₹%.1fL+".format(amount / 100_000).replace(".0L+", "L+")
            amount >= 1_000 -> "₹%.1fk+".format(amount / 1_000).replace(".0k+", "k+")
            else -> "₹%.0f+".format(amount)
        }
    }

    private fun renderDonutChartAndLegend() {
        val colors = listOf(
            "#8B5CF6", "#84CC16", "#10B981", "#F59E0B",
            "#EF4444", "#06B6D4", "#EC4899", "#3B82F6"
        )
        val totalAmt = categorySpendingMap.values.sum().coerceAtLeast(1.0)
        val sortedEntries = categorySpendingMap.entries.sortedByDescending { it.value }.take(6)

        val slices = mutableListOf<CategorySlice>()
        binding.llCategoryLegendContainer.removeAllViews()

        for ((idx, entry) in sortedEntries.withIndex()) {
            val pct = ((entry.value / totalAmt) * 100).toInt()
            val colorHex = colors[idx % colors.size]
            slices.add(CategorySlice(entry.key, entry.value, pct, colorHex))

            val shortAmt = formatShortAmount(entry.value)

            // Legend item view
            val legendRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(20, 20).apply {
                    setMargins(0, 8, 12, 0)
                }
                setBackgroundColor(Color.parseColor(colorHex))
            }
            val catText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = entry.key
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
            }
            val pctText = TextView(this).apply {
                text = "$shortAmt ($pct%)"
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            legendRow.addView(dot)
            legendRow.addView(catText)
            legendRow.addView(pctText)

            binding.llCategoryLegendContainer.addView(legendRow)
        }

        binding.cvCategoryDonut.setSlices(slices)
    }

    private fun updateBudgetWarningHeader() {
        var overCount = 0
        var nearCount = 0

        for (b in allBudgets) {
            val spent = categorySpendingMap[b.category] ?: 0.0
            val limit = b.monthly_limit
            val pct = if (limit > 0) ((spent / limit) * 100).toInt() else 0

            if (pct >= 100) overCount++
            else if (pct >= 80) nearCount++
        }

        if (overCount > 0 || nearCount > 0) {
            binding.cardBudgetWarningHeader.visibility = View.VISIBLE
            binding.tvBudgetHeaderAlertText.text = "⚠️ $overCount categories over budget\n$nearCount categories nearing limit (>80%)"
        } else {
            binding.cardBudgetWarningHeader.visibility = View.GONE
        }
    }

    // ──────────────── Actions: Manual Entry & SMS Ingest ────────────────

    private fun performSaveTransaction() {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        if (token.isEmpty()) {
            showLogin()
            return
        }

        val amountStr = binding.etManualAmount.text.toString().trim()
        val merchant = binding.etManualMerchant.text.toString().trim()
        val category = binding.spManualCategory.selectedItem.toString()

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "Please enter a valid positive amount", Toast.LENGTH_SHORT).show()
            return
        }

        val type = if (isDebitType) "debit" else "credit"
        val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.createTransaction(
                    "Bearer $token",
                    TransactionCreatePayload(amount, type, category, merchant.ifEmpty { null }, dateStr)
                )
                runOnUiThread {
                    if (resp.code() == 401) {
                        handleUnauthorized()
                        return@runOnUiThread
                    }
                    if (resp.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Transaction saved successfully! ✅", Toast.LENGTH_SHORT).show()
                        binding.etManualAmount.text?.clear()
                        binding.etManualMerchant.text?.clear()
                        switchScreen(0) // Return to Home
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to save: ${resp.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performParseSms() {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        if (token.isEmpty()) {
            showLogin()
            return
        }

        val smsText = binding.etRawSmsInput.text.toString().trim()
        if (smsText.isEmpty()) {
            Toast.makeText(this, "Please paste bank SMS text first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val payload = SmsParser.parse(smsText, "AD-BANK")
                if (payload == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Could not parse a bank transaction from this SMS.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "DEBUG: Extracted UPI: ${payload.upi_ref}", Toast.LENGTH_LONG).show()
                }

                val resp = RetrofitClient.apiService.ingestSms("Bearer $token", payload)
                runOnUiThread {
                    if (resp.code() == 401) {
                        handleUnauthorized()
                        return@runOnUiThread
                    }
                    if (resp.isSuccessful && resp.body()?.success == true) {
                        Toast.makeText(this@MainActivity, "SMS Ingested & Synced! ✅", Toast.LENGTH_SHORT).show()
                        binding.etRawSmsInput.text?.clear()
                        switchScreen(0)
                    } else {
                        val msg = resp.body()?.message ?: ""
                        if (msg.contains("duplicate", ignoreCase = true)) {
                            Toast.makeText(this@MainActivity, "Already synced automatically in background! ✅", Toast.LENGTH_LONG).show()
                            binding.etRawSmsInput.text?.clear()
                            switchScreen(0)
                        } else {
                            Toast.makeText(this@MainActivity, "Ingest failed: $msg", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncHistoricalSms() {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        if (token.isEmpty()) {
            showLogin()
            return
        }

        binding.btnSyncHistoricalSms.isEnabled = false
        binding.btnSyncHistoricalSms.text = "Syncing... Please Wait"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cursor = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
                    null, null, Telephony.Sms.DATE + " DESC"
                )

                var scanned = 0
                var count = 0
                cursor?.use {
                    val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                    val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                    val sentIdx = it.getColumnIndex(Telephony.Sms.DATE_SENT)

                    while (it.moveToNext() && scanned < 100) {
                        scanned++
                        val sender = it.getString(addressIdx) ?: ""
                        val body = it.getString(bodyIdx) ?: ""

                        // Use the SMS's own timestamp (not "now") so each historical transaction keeps its
                        // real date and matches the fingerprint of the same SMS synced by the receiver.
                        val ts = SmsReceiver.stableSmsTimestamp(it.getLong(sentIdx), it.getLong(dateIdx))
                        val payload = SmsParser.parse(body, sender, ts)
                        if (payload != null) {
                            val response = RetrofitClient.apiService.ingestSms("Bearer $token", payload)
                            if (response.isSuccessful && response.body()?.success == true) {
                                count++
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Historical sync complete! Synced $count transactions.", Toast.LENGTH_LONG).show()
                    binding.btnSyncHistoricalSms.isEnabled = true
                    binding.btnSyncHistoricalSms.text = "🔄 Sync All Existing SMS"
                    fetchDashboardData()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Sync Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    binding.btnSyncHistoricalSms.isEnabled = true
                    binding.btnSyncHistoricalSms.text = "🔄 Sync All Existing SMS"
                }
            }
        }
    }

    // ──────────────── Action: Edit Budget Dialog ────────────────

    private fun showEditBudgetDialog(budget: BudgetLimitData) {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(budget.monthly_limit))
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Budget Limit for ${budget.category}")
            .setMessage("Enter new monthly limit (₹):")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newLimit = input.text.toString().toDoubleOrNull()
                if (newLimit != null && newLimit >= 0) {
                    lifecycleScope.launch {
                        try {
                            val resp = RetrofitClient.apiService.setBudget(
                                "Bearer $token",
                                BudgetSetPayload(budget.category, newLimit)
                            )
                            runOnUiThread {
                                if (resp.code() == 401) {
                                    handleUnauthorized()
                                    return@runOnUiThread
                                }
                                if (resp.isSuccessful) {
                                    Toast.makeText(this@MainActivity, "Budget limit updated! ✅", Toast.LENGTH_SHORT).show()
                                    fetchDashboardData()
                                }
                            }
                        } catch (e: Exception) {
                            // Error
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkNeedsReviewBanner(authHeader: String) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.getNeedsReviewTransactions(authHeader)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    val unreviewedList = body.transactions
                    val count = body.count
                    runOnUiThread {
                        if (count > 0) {
                            binding.cardNeedsReviewBanner.visibility = View.VISIBLE
                            binding.tvNeedsReviewTitle.text = "Needs Review: $count Transactions"
                            binding.cardNeedsReviewBanner.setOnClickListener {
                                startNeedsReviewLoop(unreviewedList)
                            }
                        } else {
                            binding.cardNeedsReviewBanner.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                // Suppress
            }
        }
    }

    private fun startNeedsReviewLoop(unreviewed: List<TransactionData>, currentIndex: Int = 0) {
        if (currentIndex >= unreviewed.size) {
            Toast.makeText(this, "All done! SmartSpend learned your transaction rules 🎉", Toast.LENGTH_LONG).show()
            fetchDashboardData()
            return
        }
        val tx = unreviewed[currentIndex]
        val items = canonicalCategories.toTypedArray()
        val currentIdx = canonicalCategories.indexOf(tx.category).coerceAtLeast(0)

        val merchantStr = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.category
        AlertDialog.Builder(this)
            .setTitle("Needs Review (${currentIndex + 1}/${unreviewed.size}): $merchantStr (₹${"%.2f".format(tx.amount)})")
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val newCategory = items[which]
                performUpdateCategory(tx, newCategory)
                dialog.dismiss()
                startNeedsReviewLoop(unreviewed, currentIndex + 1)
            }
            .setNegativeButton("Skip", { _, _ ->
                startNeedsReviewLoop(unreviewed, currentIndex + 1)
            })
            .show()
    }

    private fun showReviewTransactionDialog(tx: TransactionData) {
        val items = canonicalCategories.toTypedArray()
        val currentIdx = canonicalCategories.indexOf(tx.category).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Review Transaction")
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val newCategory = items[which]
                performUpdateCategory(tx, newCategory)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performUpdateCategory(tx: TransactionData, newCategory: String) {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        
        lifecycleScope.launch {
            try {
                val merchantName = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.category
                val payload = RecategorizePayload(
                    transaction_id = tx.id,
                    merchant_raw = merchantName,
                    new_category = newCategory
                )
                val resp = RetrofitClient.apiService.recategorizeTransaction("Bearer $token", tx.id, payload)
                
                runOnUiThread {
                    if (resp.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Category updated! ✅", Toast.LENGTH_SHORT).show()
                        fetchDashboardData()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to update: ${resp.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showTransactionActionMenu(tx: TransactionData) {
        val options = arrayOf("Edit Transaction", "Delete Transaction", "Change Category")
        AlertDialog.Builder(this)
            .setTitle(tx.merchant ?: tx.category)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFullEditTransactionDialog(tx)
                    1 -> confirmDeleteTransaction(tx)
                    2 -> showReviewTransactionDialog(tx)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFullEditTransactionDialog(tx: TransactionData) {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etMerchant = EditText(this).apply {
            hint = "Merchant / Payee"
            setText(tx.merchant ?: "")
        }
        val etAmount = EditText(this).apply {
            hint = "Amount (₹)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(tx.amount))
        }

        val spinnerCategory = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, canonicalCategories)
        spinnerCategory.adapter = adapter
        val currentIdx = canonicalCategories.indexOf(tx.category).coerceAtLeast(0)
        spinnerCategory.setSelection(currentIdx)

        layout.addView(TextView(this).apply { text = "Merchant:"; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        layout.addView(etMerchant)
        layout.addView(TextView(this).apply { text = "Category:"; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        layout.addView(spinnerCategory)
        layout.addView(TextView(this).apply { text = "Amount (₹):"; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        layout.addView(etAmount)

        AlertDialog.Builder(this)
            .setTitle("Edit Transaction #${tx.id}")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val updatedMerchant = etMerchant.text.toString().trim()
                val updatedCategory = canonicalCategories[spinnerCategory.selectedItemPosition]
                val updatedAmount = etAmount.text.toString().toDoubleOrNull() ?: tx.amount

                val payload = TransactionUpdatePayload(
                    merchant = updatedMerchant,
                    category = updatedCategory,
                    amount = updatedAmount
                )

                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.patchTransaction("Bearer $token", tx.id, payload)
                        runOnUiThread {
                            if (resp.isSuccessful) {
                                Toast.makeText(this@MainActivity, "Transaction updated! ✅", Toast.LENGTH_SHORT).show()
                                fetchDashboardData()
                            } else {
                                Toast.makeText(this@MainActivity, "Update failed: ${resp.code()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTransaction(tx: TransactionData) {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Transaction?")
            .setMessage("Are you sure you want to permanently delete transaction for ₹${"%.2f".format(tx.amount)}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.deleteTransaction("Bearer $token", tx.id)
                        runOnUiThread {
                            if (resp.isSuccessful) {
                                Toast.makeText(this@MainActivity, "Transaction deleted! 🗑️", Toast.LENGTH_SHORT).show()
                                fetchDashboardData()
                            } else {
                                Toast.makeText(this@MainActivity, "Delete failed: ${resp.code()}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ──────────────── Auth & Profile ────────────────

    private fun launchGoogleSignIn() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        binding.btnGoogleSignIn.isClickable = false
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                binding.btnGoogleSignIn.isClickable = true
                if (task.isSuccessful) {
                    val fbUser = auth.currentUser
                    val email = fbUser?.email ?: "google_user@smartspend.app"
                    val displayName = fbUser?.displayName ?: email.substringBefore("@")
                    sharedPrefs.edit()
                        .putString("jwt_token", "google_${fbUser?.uid}")
                        .putString("user_email", email)
                        .apply()
                    Toast.makeText(this, "Welcome, $displayName! 👋", Toast.LENGTH_SHORT).show()
                    showDashboard()
                } else {
                    Toast.makeText(this, "Google Sign-In failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val fullName = binding.etFullName.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isRegisterMode && fullName.isEmpty()) {
            Toast.makeText(this, "Please enter your full name", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = if (isRegisterMode) "Creating Account..." else "Signing in..."

        lifecycleScope.launch {
            try {
                // Authenticate with backend API for JWT
                val resp = if (isRegisterMode) {
                    RetrofitClient.apiService.register(RegisterPayload(email, fullName, password))
                } else {
                    RetrofitClient.apiService.login(email, password)
                }
                
                if (resp.isSuccessful && resp.body() != null) {
                    val token = resp.body()!!.access_token
                    sharedPrefs.edit()
                        .putString("jwt_token", token)
                        .putString("user_email", email)
                        .apply()

                    // 2. Synchronize with Firebase Auth
                    try {
                        auth.signInWithEmailAndPassword(email, password)
                            .addOnCompleteListener { fbTask ->
                                if (!fbTask.isSuccessful) {
                                    auth.createUserWithEmailAndPassword(email, password)
                                }
                            }
                    } catch (fbEx: Exception) {
                        // Suppress Firebase sync errors
                    }

                    withContext(Dispatchers.Main) {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                        val welcomeMsg = if (isRegisterMode) "Account created! Welcome, $fullName! 👋" else "Welcome back, $email! 👋"
                        Toast.makeText(this@MainActivity, welcomeMsg, Toast.LENGTH_SHORT).show()
                        showDashboard()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.btnLogin.isEnabled = true
                        binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                        Toast.makeText(this@MainActivity, "Login failed: ${resp.code()} (Invalid credentials)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnLogin.isEnabled = true
                    binding.btnLogin.text = if (isRegisterMode) "Sign Up" else "Sign In"
                    Toast.makeText(this@MainActivity, "Connection error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderProfileDetails() {
        val userEmail = sharedPrefs.getString("user_email", "user@example.com") ?: "user@example.com"
        val fbUser = auth.currentUser

        binding.tvProfileEmail.text = fbUser?.email ?: userEmail
        val derivedName = fbUser?.displayName?.takeIf { it.isNotBlank() }
            ?: userEmail.substringBefore("@").replace(".", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        binding.tvProfileDisplayName.text = derivedName

        val initial = if (derivedName.isNotBlank()) derivedName.take(1).uppercase() else "👤"
        binding.tvProfileAvatarLarge.text = initial
        binding.tvProfileAvatarInitial.text = initial

        val uid = fbUser?.uid ?: ("UID-" + userEmail.hashCode().toString().takeLast(8))
        binding.tvProfileFirebaseUid.text = if (uid.length > 16) uid.take(16) + "..." else uid
        binding.tvProfileAuthProvider.text = if (fbUser != null) "Firebase Auth" else "Firebase / JWT"

        val totalSynced = sharedPrefs.getInt("total_synced", allTransactions.size)
        binding.tvProfileTotalSyncedCount.text = "$totalSynced Transactions"

        val fcmToken = sharedPrefs.getString("fcm_token", null)
        binding.tvProfileFcmTokenStatus.text = if (!fcmToken.isNullOrEmpty()) "Connected 🔔" else "Registered"
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out of SmartSpend?")
            .setPositiveButton("Sign Out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAccountWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete account permanently?")
            .setMessage(
                "This permanently deletes your account, transactions, budgets, and merchant categorization history. " +
                    "This cannot be undone."
            )
            .setPositiveButton("Continue") { _, _ -> showDeleteAccountConfirmationDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle("SmartSpend Privacy Policy")
            .setMessage(PRIVACY_POLICY_PLACEHOLDER)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showDeleteAccountConfirmationDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
        }
        val passwordInput = EditText(this).apply {
            hint = "Current password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmationInput = EditText(this).apply {
            hint = "Type DELETE to confirm"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val isGoogleAccount = auth.currentUser?.providerData?.any { it.providerId == "google.com" } == true
        if (!isGoogleAccount) {
            container.addView(passwordInput)
        }
        container.addView(confirmationInput)

        AlertDialog.Builder(this)
            .setTitle("Confirm account deletion")
            .setMessage("Enter DELETE exactly and confirm your identity.")
            .setView(container)
            .setPositiveButton("Delete account", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val confirmation = confirmationInput.text.toString().trim()
                        if (!confirmation.equals("DELETE", ignoreCase = true)) {
                            confirmationInput.error = "Type DELETE to confirm"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        deleteAccount(
                            password = if (isGoogleAccount) null else passwordInput.text.toString()
                        )
                    }
                }
                dialog.show()
            }
    }

    private fun deleteAccount(password: String?) {
        val token = sharedPrefs.getString("jwt_token", null)
        if (token.isNullOrEmpty()) {
            showLogin()
            return
        }

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.deleteMyAccount(
                    "Bearer $token",
                    DeleteAccountPayload(password = password, confirmation_text = "DELETE")
                )
                if (response.isSuccessful && response.body()?.success == true) {
                    try {
                        auth.signOut()
                        googleSignInClient.signOut()
                    } catch (_: Exception) {
                        // Local session cleanup still proceeds if provider sign-out fails.
                    }
                    sharedPrefs.edit().clear().apply()
                    allTransactions = emptyList()
                    allBudgets = emptyList()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Your account has been deleted.", Toast.LENGTH_LONG).show()
                        showLogin()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Account deletion failed (${response.code()}).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not delete account: ${e.localizedMessage ?: "network error"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun performLogout() {
        try {
            auth.signOut()
            googleSignInClient.signOut()
        } catch (e: Exception) {
            // Suppress
        }
        sharedPrefs.edit()
            .remove("jwt_token")
            .remove("user_email")
            .apply()

        Toast.makeText(this, "Logged out successfully ✅", Toast.LENGTH_SHORT).show()
        showLogin()
    }

    private fun updateSyncHubStats() {
        val userEmail = sharedPrefs.getString("user_email", "—") ?: "—"
        binding.tvUserEmail.text = userEmail
        val initial = if (userEmail.isNotBlank() && userEmail != "—") userEmail.take(1).uppercase() else "👤"
        binding.tvProfileAvatarInitial.text = initial
    }

    private fun checkPermissions() {
        val receiveSmsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val readSmsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        if (!receiveSmsGranted || !readSmsGranted) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            )
        }
    }

    // ──────────────── Section 4: AI Insights Dashboard Renderer ────────────────

    private fun fetchInsightsData() {
        val token = sharedPrefs.getString("jwt_token", "") ?: return
        if (token.isEmpty()) return

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.getInsightsSummary("Bearer $token")
                runOnUiThread {
                    if (resp.isSuccessful && resp.body() != null) {
                        renderInsightsUI(resp.body()!!)
                    } else {
                        renderFallbackInsightsUI()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    renderFallbackInsightsUI()
                }
            }
        }
    }

    private fun renderInsightsUI(data: InsightsSummaryData) {
        // Section 1: Spending Changes
        binding.containerSpendingChanges.removeAllViews()
        val changes = data.spending_changes
            ?.filter { !it.not_enough_data && it.change_percent != null }
            ?.sortedByDescending { it.change_percent ?: 0.0 }
            ?.take(5)
        if (changes.isNullOrEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No historical spending changes calculated yet."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
            }
            binding.containerSpendingChanges.addView(emptyTv)
        } else {
            for (c in changes) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                val icon = TextView(this).apply {
                    text = if (c.direction == "up") "📈" else "📉"
                    textSize = 16f
                    setPadding(0, 0, 12, 0)
                }
                val name = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = c.category
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val pct = TextView(this).apply {
                    text = "${if (c.direction == "up") "+" else "-"}%.1f%%".format(c.change_percent ?: 0.0)
                    setTextColor(if (c.direction == "up") Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                row.addView(icon)
                row.addView(name)
                row.addView(pct)
                binding.containerSpendingChanges.addView(row)
            }
        }

        // Section 2: Anomalies
        binding.containerAnomalies.removeAllViews()
        val anomalies = data.anomalies?.take(5)
        if (anomalies.isNullOrEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No unusual spending spikes detected this month. ✅"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 12f
            }
            binding.containerAnomalies.addView(emptyTv)
        } else {
            for (a in anomalies) {
                val cat = a["category"]?.toString() ?: "Spending Spike"
                val amt = (a["amount"] as? Number)?.toDouble() ?: 0.0
                val avg = ((a["avg"] ?: a["avg_amount"]) as? Number)?.toDouble() ?: 0.0

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#332A2923"))
                    setPadding(20, 14, 20, 14)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 10)
                    }
                }
                val isBudgetAlert = a["kind"]?.toString() == "budget"
                val title = TextView(this).apply {
                    text = if (isBudgetAlert) "OVER BUDGET ($cat)" else "SPENDING SPIKE ($cat)"
                    setTextColor(if (isBudgetAlert) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val sub = TextView(this).apply {
                    text = "Spent ₹%.2f (Category Avg: ₹%.2f)".format(amt, avg)
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 12f
                }
                card.addView(title)
                card.addView(sub)
                binding.containerAnomalies.addView(card)
            }
        }

        // Section 3: Budget Alerts
        binding.containerBudgetAlerts.removeAllViews()
        val alerts = data.budget_alerts
        if (alerts.isNullOrEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "All categories are within healthy budget limits. ✅"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 12f
            }
            binding.containerBudgetAlerts.addView(emptyTv)
        } else {
            for (b in alerts) {
                val cat = b["category"]?.toString() ?: "Category"
                val spent = (b["spent"] as? Number)?.toDouble() ?: 0.0
                val limit = (b["limit"] as? Number)?.toDouble() ?: 1.0
                val pct = ((spent / limit) * 100).toInt()

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 8, 0, 12)
                }
                val rowHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val catTv = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = cat
                    setTextColor(Color.parseColor("#F8FAFC"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val pctTv = TextView(this).apply {
                    text = "$pct% used"
                    setTextColor(if (pct >= 100) Color.parseColor("#EF4444") else Color.parseColor("#F59E0B"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                rowHeader.addView(catTv)
                rowHeader.addView(pctTv)
                card.addView(rowHeader)
                binding.containerBudgetAlerts.addView(card)
            }
        }
    }

    private fun renderFallbackInsightsUI() {
        val debits = allTransactions.filter { it.type.equals("debit", ignoreCase = true) }
        val avgSpend = if (debits.isNotEmpty()) debits.map { it.amount }.average() else 0.0
        val spikes = debits.filter { it.amount >= (avgSpend * 2.0) && it.amount > 500.0 }

        val computedData = InsightsSummaryData(
            spending_changes = emptyList(),
            anomalies = spikes.map { tx ->
                mapOf(
                    "category" to tx.category,
                    "amount" to tx.amount,
                    "avg_amount" to avgSpend
                )
            },
            recurring = emptyList(),
            budget_alerts = allBudgets.mapNotNull { b ->
                val spent = categorySpendingMap[b.category] ?: 0.0
                if (spent >= (b.monthly_limit * 0.8)) {
                    mapOf("category" to b.category, "spent" to spent, "limit" to b.monthly_limit)
                } else null
            }
        )
        renderInsightsUI(computedData)
    }

    companion object {
        private const val PAGE_SIZE = 10
        private const val PRIVACY_POLICY_PLACEHOLDER = """
Last updated: [DATE]
Effective for: SmartSpend, developed by [YOUR NAME / COMPANY NAME]
Developer contact: [your.email@example.com]

SmartSpend helps users understand their spending by reading and categorizing bank transaction SMS alerts.

With your permission, SmartSpend reads incoming SMS messages only from a whitelisted list of known bank sender IDs. SMS parsing happens locally on your device. The original SMS text is not sent to SmartSpend servers; the app sends only structured transaction details such as amount, debit or credit type, merchant or payee, masked account suffix, transaction date, bank sender ID, and UPI reference when available.

SmartSpend also collects account information needed for sign-in, transaction and budget data you create or sync, and basic usage or crash diagnostics if enabled. [Confirm and specify analytics or crash SDKs before publishing.]

We use this data to authenticate you, sync your account, categorize transactions, generate budgets and insights, respond to support requests, and keep the service secure. We do not use financial or SMS data for advertising, and we do not sell your data.

Your data is stored in PostgreSQL behind the SmartSpend backend API. Data in transit is encrypted with HTTPS/TLS. Passwords are hashed with bcrypt, authentication uses JWT bearer tokens, and Google Sign-In is handled through Firebase Authentication.

You can view, export, edit, recategorize, and delete your transaction data. You can permanently delete your account from Profile -> Account & Data -> Delete My Account. If you cannot access the app, email [your.email@example.com] or use [web deletion link URL].

Permissions: SMS read/receive access is optional and used for bank transaction auto-logging. Internet access is required for account sync.

SmartSpend is not directed at children under 13 or the applicable minimum age in your jurisdiction. Material policy changes will be reflected with an updated date and may be communicated in-app.
"""
    }
}
