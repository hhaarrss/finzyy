package com.smartspend.app.ui.home

import android.widget.Toast
import com.smartspend.app.ui.components.rememberTransactionsVersion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.smartspend.app.ui.components.Guilloche
import com.smartspend.app.ui.components.Hairline
import com.smartspend.app.ui.components.ShareStrip
import com.smartspend.app.ui.components.foldToShares
import com.smartspend.app.ui.theme.LedgerAmount
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.smartspend.app.ui.notifications.AttentionRepository
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartspend.app.HomeCategoryData
import com.smartspend.app.HomeData
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.ErrorPanel
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.components.MerchantAvatar
import com.smartspend.app.ui.components.RoundIconButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.SectionTitle
import com.smartspend.app.ui.components.SkeletonBlocks
import com.smartspend.app.ui.components.FinzyyIcons
import com.smartspend.app.ui.components.TextAction
import com.smartspend.app.ui.components.ThinProgress
import com.smartspend.app.ui.components.TransactionRow
import com.smartspend.app.ui.components.TransactionSheet
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.components.greetingFor
import com.smartspend.app.ui.components.money
import com.smartspend.app.ui.components.monthName
import com.smartspend.app.ui.components.toView
import com.smartspend.app.ui.permission.rememberSmsPermissionsGranted
import com.smartspend.app.ui.theme.FinzyyTheme
import com.smartspend.app.ui.tour.LocalTourController
import com.smartspend.app.ui.tour.TourController
import com.smartspend.app.ui.tour.TourOverlay
import com.smartspend.app.ui.tour.TourStep
import com.smartspend.app.ui.tour.tourTarget
import kotlinx.coroutines.delay
import com.smartspend.app.ui.theme.TabularAmount
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale
import kotlin.math.absoluteValue

private data class HomeBundle(val home: HomeData, val overallBudget: Double?)

private sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val bundle: HomeBundle) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearch: (reviewOnly: Boolean) -> Unit,
    onAccount: () -> Unit,
    onAddTransaction: () -> Unit,
    onBudget: () -> Unit,
    onTrends: () -> Unit,
    onCategories: () -> Unit,
    onCategory: (String) -> Unit,
    onInsights: () -> Unit,
    onEnableAutoSync: () -> Unit,
    onNotifications: () -> Unit,
    showTour: Boolean = false,
    onTourDone: () -> Unit = {}
) {
    val context = LocalContext.current
    val attentionCount by AttentionRepository.unread.collectAsState()
    var state by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    val listState = rememberLazyListState()
    val tour = remember(showTour) { if (showTour) TourController() else null }
    var tourVisible by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val liveVersion = rememberTransactionsVersion()
    var refreshing by remember { mutableStateOf(false) }
    var openTx by remember { mutableStateOf<TxView?>(null) }

    // The bell's badge: refreshed whenever Home reloads. Failures just leave the last count.
    LaunchedEffect(refreshKey, liveVersion) {
        runCatching { AttentionRepository.load(context) }
    }

    LaunchedEffect(refreshKey, liveVersion) {
        if (state !is HomeUiState.Loaded) state = HomeUiState.Loading
        state = try {
            coroutineScope {
                val home = async { RetrofitClient.apiService.getHomeData("") }
                val overall = async { runCatching { RetrofitClient.apiService.getOverallBudget() }.getOrNull() }
                val homeResponse = home.await()
                val body = homeResponse.body()
                if (homeResponse.isSuccessful && body != null) {
                    val limit = overall.await()?.takeIf { it.isSuccessful }?.body()?.monthly_limit
                    HomeUiState.Loaded(HomeBundle(body, limit?.takeIf { it > 0 }))
                } else {
                    HomeUiState.Error("The server answered ${homeResponse.code()}. Pull down to retry.")
                }
            }
        } catch (e: Exception) {
            (state as? HomeUiState.Loaded) ?: HomeUiState.Error(e.localizedMessage ?: "Can't reach Finzyy right now.")
        }
        refreshing = false
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
    val loaded = state is HomeUiState.Loaded
    LaunchedEffect(showTour, loaded) {
        if (showTour && loaded && !tourVisible) {
            delay(TOUR_START_DELAY_MS) // let the screen settle so the first stop isn't a moving target
            tourVisible = true
        }
    }

    CompositionLocalProvider(LocalTourController provides tour) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; refreshKey++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val current = state) {
                HomeUiState.Loading -> Column {
                    TopBar(name = null, attentionCount = attentionCount, onAccount = onAccount, onNotifications = onNotifications, onSearch = { onSearch(false) })
                    SkeletonBlocks(listOf(200.dp, 104.dp, 64.dp, 300.dp))
                }
                is HomeUiState.Error -> Column {
                    TopBar(name = null, attentionCount = attentionCount, onAccount = onAccount, onNotifications = onNotifications, onSearch = { onSearch(false) })
                    ErrorPanel(current.message, onRetry = { refreshKey++ })
                }
                is HomeUiState.Loaded -> HomeContent(
                    bundle = current.bundle,
                    attentionCount = attentionCount,
                    onNotifications = onNotifications,
                    onSearch = onSearch,
                    onAccount = onAccount,
                    onAddTransaction = onAddTransaction,
                    onBudget = onBudget,
                    onTrends = onTrends,
                    onCategories = onCategories,
                    onCategory = onCategory,
                    onInsights = onInsights,
                    onEnableAutoSync = onEnableAutoSync,
                    onOpenTx = { openTx = it },
                    listState = listState
                )
            }
        }
    }
    }

    openTx?.let { tx ->
        TransactionSheet(tx = tx, onDismiss = { openTx = null }, onChanged = { refreshKey++ })
    }

    if (tourVisible && tour != null) {
        TourOverlay(
            controller = tour,
            steps = HomeTourSteps,
            listState = listState,
            onFinish = {
                tourVisible = false
                onTourDone()
            }
        )
    }
}

private const val TOUR_START_DELAY_MS = 700L

/** Top-to-bottom, so the tour never has to scroll back up. */
private val HomeTourSteps = listOf(
    TourStep("account", "Your account", "Profile, theme, alerts and SMS sync settings live here."),
    TourStep("notifications", "What needs you", "Payments we couldn't categorise, budgets running over and unusual spending collect here. The badge counts what you haven't seen yet."),
    TourStep("search", "Find any payment", "Search by merchant, category, bank or amount -- even \"499\"."),
    TourStep("hero", "This month at a glance", "What you've spent so far. With a budget set, the bar shows how much is gone and the tick marks where today falls."),
    TourStep("budget", "Set a monthly budget", "Cap your spending overall or per category -- we'll warn you before you go over, not after."),
    TourStep("links", "Trends and categories", "See spending over time, or exactly where each rupee went."),
    TourStep("add", "Add a payment by hand", "Paid in cash? Add it here. Bank SMS payments are added for you automatically.")
)

@Composable
private fun HomeContent(
    bundle: HomeBundle,
    attentionCount: Int,
    onNotifications: () -> Unit,
    onSearch: (Boolean) -> Unit,
    onAccount: () -> Unit,
    onAddTransaction: () -> Unit,
    onBudget: () -> Unit,
    onTrends: () -> Unit,
    onCategories: () -> Unit,
    onCategory: (String) -> Unit,
    onInsights: () -> Unit,
    onEnableAutoSync: () -> Unit,
    onOpenTx: (TxView) -> Unit,
    listState: LazyListState
) {
    val data = bundle.home
    val autoSyncOn = rememberSmsPermissionsGranted()
    val pace = remember(data) { monthPace(data) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TopBar(
                name = displayName(data),
                attentionCount = attentionCount,
                onAccount = onAccount,
                onNotifications = onNotifications,
                onSearch = { onSearch(false) }
            )
        }

        item {
            SpendHero(
                data = data,
                overallBudget = bundle.overallBudget,
                modifier = Modifier.padding(horizontal = ScreenGutter).tourTarget("hero")
            )
        }

        item {
            IncomeBudgetBlock(
                income = data.overview.total_income,
                month = monthName(data.month, data.year, "MMM"),
                limit = bundle.overallBudget,
                onBudget = onBudget,
                modifier = Modifier.padding(horizontal = ScreenGutter)
            )
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = ScreenGutter).tourTarget("links"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinkPill(FinzyyIcons.Trends, "Trends", onTrends, Modifier.weight(1f))
                LinkPill(FinzyyIcons.Categories, "Categories", onCategories, Modifier.weight(1f))
            }
        }

        if (pace != null) {
            item {
                InsightTeaser(
                    pace = pace,
                    overallBudget = bundle.overallBudget,
                    onClick = onInsights,
                    modifier = Modifier.padding(horizontal = ScreenGutter)
                )
            }
        }

        if (data.overview.needs_review_count > 0) {
            item {
                NeedsReviewBanner(
                    count = data.overview.needs_review_count,
                    onClick = { onSearch(true) },
                    modifier = Modifier.padding(horizontal = ScreenGutter)
                )
            }
        }

        if (!autoSyncOn) {
            item {
                AutoSyncPrompt(onEnableAutoSync, Modifier.padding(horizontal = ScreenGutter))
            }
        }

        // ── Recent transactions ────────────────────────────────────────────
        item {
            SectionTitle(
                "Recent",
                modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp)
            ) {
                AddPill(onClick = onAddTransaction, modifier = Modifier.tourTarget("add"))
            }
        }
        item {
            Column(Modifier.padding(horizontal = ScreenGutter - 16.dp)) {
                if (data.recent_transactions.isEmpty()) {
                    Text(
                        "Nothing yet this month. Transactions from bank SMS land here automatically, or add one by hand.",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinzyyTheme.colors.inkMuted
                    )
                } else {
                    data.recent_transactions.take(5).forEachIndexed { i, raw ->
                        if (i > 0) Hairline(Modifier.padding(start = 68.dp, end = 16.dp))
                        val tx = raw.toView()
                        TransactionRow(tx = tx, onClick = { onOpenTx(tx) })
                    }
                    Hairline(Modifier.padding(horizontal = 16.dp))
                    SeeAllRow("See all transactions", onClick = { onSearch(false) })
                }
            }
        }

        // ── Categories ─────────────────────────────────────────────────────
        if (data.top_categories.isNotEmpty()) {
            item {
                SectionTitle(
                    "Where it went",
                    modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp)
                ) { TextAction("All →", onClick = onCategories) }
            }
            item {
                WhereItWent(
                    categories = data.top_categories,
                    onCategory = onCategory,
                    modifier = Modifier.padding(horizontal = ScreenGutter)
                )
            }
        }

        // ── Splits (preview only) ──────────────────────────────────────────
        item {
            SectionTitle(
                "Split expenses",
                modifier = Modifier.padding(start = ScreenGutter, end = ScreenGutter, top = 16.dp)
            ) { SoonTag() }
        }
        item { SplitsPreview(Modifier.padding(horizontal = ScreenGutter)) }
    }
}

@Composable
private fun TopBar(
    name: String?,
    attentionCount: Int,
    onAccount: () -> Unit,
    onNotifications: () -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenGutter, end = ScreenGutter - 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .tourTarget("account")
                .clickable(role = Role.Button, onClick = onAccount),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name?.firstOrNull()?.uppercase() ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    greetingFor(LocalTime.now().hour),
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
                Text(
                    name ?: " ",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Same button as search, same icon set and size; the count is items still needing attention.
        RoundIconButton(
            icon = Icons.Default.Notifications,
            contentDescription = if (attentionCount > 0) "Notifications, $attentionCount need attention" else "Notifications",
            onClick = onNotifications,
            badgeCount = attentionCount,
            modifier = Modifier.tourTarget("notifications")
        )
        Spacer(Modifier.width(6.dp))
        RoundIconButton(
            icon = Icons.Default.Search,
            contentDescription = "Search transactions",
            onClick = onSearch,
            modifier = Modifier.tourTarget("search")
        )
    }
}

/**
 * The number the screen exists for, printed like a banknote: guilloché engraving behind it,
 * a serial in the corner, and a statement-thin meter that answers "am I OK?" — with a tick
 * marking where today falls in the month, so pace reads without a second number.
 */
@Composable
private fun SpendHero(data: HomeData, overallBudget: Double?, modifier: Modifier = Modifier) {
    val colors = FinzyyTheme.colors
    val spent = data.overview.total_spent
    val ym = runCatching { YearMonth.of(data.year, data.month) }.getOrNull()
    val today = LocalDate.now()
    val dayOfMonth = if (ym != null && ym == YearMonth.from(today)) today.dayOfMonth else ym?.lengthOfMonth() ?: 30
    val daysIn = ym?.lengthOfMonth() ?: 30

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.heroSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline)
    ) {
        Box {
            Guilloche(color = colors.accent, fadeTo = colors.heroSurface, modifier = Modifier.matchParentSize())
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Spent · ${monthName(data.month, data.year)}", modifier = Modifier.weight(1f))
                    Text(
                        "SS ${"%02d".format(data.month)}·${data.year}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkFaint
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "₹",
                        modifier = Modifier.padding(top = 6.dp, end = 3.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onHeroSurface
                    )
                    Text(
                        moneyDigits(spent),
                        style = MaterialTheme.typography.displayLarge.merge(TabularAmount).copy(textAlign = TextAlign.Start),
                        color = colors.onHeroSurface,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (overallBudget != null) {
                    val ratio = (spent / overallBudget).toFloat()
                    BudgetMeter(ratio = ratio, dayFraction = dayOfMonth / daysIn.toFloat())
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            buildAnnotatedString {
                                if (spent <= overallBudget) {
                                    withStyle(SpanStyle(color = colors.onHeroSurface, fontWeight = FontWeight.SemiBold)) { append(money(overallBudget - spent)) }
                                    append(" left of ${money(overallBudget)}")
                                } else {
                                    withStyle(SpanStyle(color = colors.negative, fontWeight = FontWeight.SemiBold)) { append(money(spent - overallBudget)) }
                                    append(" over ${money(overallBudget)}")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted
                        )
                        Text("day $dayOfMonth of $daysIn", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                } else {
                    MomLine(data.overview.mom_change_percent, spent, colors.onHeroSurface)
                }
            }
        }
    }
}

/** Budget used, with a tick where "today" falls in the month — ahead of the tick means over pace. */
@Composable
private fun BudgetMeter(ratio: Float, dayFraction: Float) {
    val colors = FinzyyTheme.colors
    val fill = if (ratio > 1f) colors.negative else MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(11.dp)
    ) {
        val y = size.height / 2f
        val h = 3.dp.toPx()
        drawRect(colors.hairline, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h))
        drawRect(fill, androidx.compose.ui.geometry.Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * ratio.coerceIn(0f, 1f), h))
        val tx = size.width * dayFraction.coerceIn(0f, 1f)
        drawRect(colors.inkMuted, androidx.compose.ui.geometry.Offset(tx, 0f), androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
    }
}

@Composable
private fun MomLine(momPercent: Double, spent: Double, onHero: Color) {
    if (momPercent == 0.0 || momPercent <= -100.0) {
        Text(
            "Set a monthly budget to see how much is left.",
            style = MaterialTheme.typography.bodyMedium,
            color = onHero.copy(alpha = 0.8f)
        )
        return
    }
    val less = momPercent < 0
    val previous = spent / (1 + momPercent / 100.0)
    val delta = (spent - previous).absoluteValue
    Text(
        "${if (less) "▼" else "▲"} ${money(delta)} ${if (less) "less" else "more"} than last month so far",
        style = MaterialTheme.typography.bodySmall,
        color = if (less) FinzyyTheme.colors.positive else onHero
    )
}

@Composable
private fun IncomeBudgetBlock(
    income: Double,
    month: String,
    limit: Double?,
    onBudget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = FinzyyTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline)
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Eyebrow("Income")
                Text(
                    if (income > 0) "+${money(income)}" else money(0.0),
                    style = MaterialTheme.typography.headlineSmall.merge(TabularAmount).copy(textAlign = TextAlign.Start),
                    color = if (income > 0) colors.positive else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text("received in $month", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.hairline))
            Column(
                Modifier
                    .weight(1f)
                    .tourTarget("budget")
                    .clickable(role = Role.Button, onClick = onBudget)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Eyebrow("Monthly budget")
                Text(
                    limit?.let(::money) ?: "Not set",
                    style = MaterialTheme.typography.headlineSmall.merge(TabularAmount).copy(textAlign = TextAlign.Start),
                    color = if (limit != null) MaterialTheme.colorScheme.onSurface else colors.inkMuted,
                    maxLines = 1
                )
                Row {
                    Text(if (limit != null) "Edit" else "Set budget", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun LinkPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, FinzyyTheme.colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

private data class Pace(val projected: Double, val monthEnd: LocalDate)

/** Straight-line month-end projection. Too noisy to show before the 3rd of the month. */
private fun monthPace(data: HomeData): Pace? {
    val today = LocalDate.now()
    if (today.year != data.year || today.monthValue != data.month) return null
    val daysElapsed = today.dayOfMonth
    if (daysElapsed < 3 || data.overview.total_spent <= 0) return null
    val ym = YearMonth.of(data.year, data.month)
    val projected = data.overview.total_spent / daysElapsed * ym.lengthOfMonth()
    return Pace(projected, ym.atEndOfMonth())
}

@Composable
private fun InsightTeaser(pace: Pace, overallBudget: Double?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val over = overallBudget != null && pace.projected > overallBudget
    Block(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        padding = PaddingValues(16.dp),
        onClick = onClick,
        color = if (over) FinzyyTheme.colors.negativeContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FinzyyIcons.Insights,
                contentDescription = null,
                tint = if (over) FinzyyTheme.colors.negative else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "At this pace: ${money(pace.projected)} by ${pace.monthEnd.dayOfMonth} ${monthName(pace.monthEnd.monthValue, pace.monthEnd.year, "MMM")}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        overallBudget == null -> "See what's driving it"
                        over -> "${money(pace.projected - overallBudget)} over your budget — see what's driving it"
                        else -> "${money(overallBudget - pace.projected)} under your budget"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
            }
            Text("Insights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NeedsReviewBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Block(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        padding = PaddingValues(16.dp),
        onClick = onClick,
        color = FinzyyTheme.colors.cautionContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 transaction needs a category" else "$count transactions need a category",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Tag them once — we'll remember the merchant",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
            }
            Text("Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AutoSyncPrompt(onEnable: () -> Unit, modifier: Modifier = Modifier) {
    Block(modifier = modifier, shape = MaterialTheme.shapes.medium, padding = PaddingValues(16.dp), onClick = onEnable) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FinzyyIcons.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Auto-sync is off", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Log bank SMS automatically instead of by hand",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
            }
            Text("Turn on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AddPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onBackground,
        contentColor = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("ADD", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SeeAllRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Share of the month by category: one engraved strip of ink shades, then a row per shade with
 * its name, share and amount — the rows are the readable twin of the strip.
 */
@Composable
private fun WhereItWent(categories: List<HomeCategoryData>, onCategory: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = FinzyyTheme.colors
    val slices = remember(categories, colors) {
        foldToShares(categories, { it.spent }, { it.category }, colors.shareShades)
    }
    val total = slices.sumOf { it.value }.coerceAtLeast(0.01)
    Column(modifier) {
        ShareStrip(slices, Modifier.padding(top = 4.dp, bottom = 10.dp))
        slices.forEachIndexed { i, slice ->
            if (i > 0) Hairline()
            val tappable = slice.label != "Everything else"
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (tappable) Modifier.clickable(role = Role.Button) { onCategory(slice.label) } else Modifier)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(10.dp).background(slice.color, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                Text(slice.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text("${(slice.value / total * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.weight(1f))
                Text(money(slice.value), style = LedgerAmount, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun SoonTag() {
    Surface(shape = CircleShape, color = FinzyyTheme.colors.subtleSurface) {
        Text(
            "COMING SOON",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = FinzyyTheme.colors.inkMuted
        )
    }
}

/**
 * Splits is future scope — this card previews the feature so the Home layout doesn't shift
 * when it ships. Tapping explains that rather than opening a half-built flow.
 */
@Composable
private fun SplitsPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Block(
        modifier = modifier,
        onClick = { Toast.makeText(context, "Splitting bills is coming in a future update", Toast.LENGTH_SHORT).show() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(76.dp).height(40.dp)) {
                listOf("A", "R", "+").forEachIndexed { i, letter ->
                    Box(
                        modifier = Modifier
                            .offset(x = (i * 22).dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (letter == "+") FinzyyTheme.colors.subtleSurface else MaterialTheme.colorScheme.primaryContainer)
                            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            letter,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (letter == "+") FinzyyTheme.colors.inkMuted else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Split a bill", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Add friends to a transaction and track who owes what",
                    style = MaterialTheme.typography.bodySmall,
                    color = FinzyyTheme.colors.inkMuted
                )
            }
        }
    }
}

private fun displayName(data: HomeData): String =
    data.user.full_name?.takeIf { it.isNotBlank() }
        ?: data.user.email?.substringBefore("@")?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: "there"

/** Grouped digits without the currency sign, for the hero where ₹ is set smaller beside them. */
private fun moneyDigits(value: Double): String = money(value).replace("₹", "").trim()
