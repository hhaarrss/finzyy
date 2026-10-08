package com.smartspend.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartspend.app.ui.account.AccountScreen
import com.smartspend.app.ui.addtransaction.AddTransactionScreen
import com.smartspend.app.ui.budget.BudgetScreen
import com.smartspend.app.ui.categories.CategoriesScreen
import com.smartspend.app.ui.categories.CategoryDetailScreen
import com.smartspend.app.ui.home.HomeScreen
import com.smartspend.app.ui.insights.InsightsScreen
import com.smartspend.app.ui.notifications.NotificationsScreen
import com.smartspend.app.ui.permission.SmsConsentScreen
import com.smartspend.app.RetrofitClient
import com.smartspend.app.UserData
import com.smartspend.app.ui.auth.LinkPhoneScreen
import com.smartspend.app.ui.auth.ProfileSetupScreen
import androidx.compose.runtime.produceState
import com.smartspend.app.ui.search.SearchScreen
import com.smartspend.app.ui.trends.TrendsScreen

/**
 * A tap that lands while the current entry is mid-transition would otherwise push the
 * destination twice, leaving a duplicate on the back stack that the user has to dismiss
 * two times. Dropping events from a non-resumed entry is the standard guard.
 */
private fun NavHostController.navigateTo(route: String) {
    val isResumed = currentBackStackEntry?.lifecycle?.currentState
        ?.isAtLeast(Lifecycle.State.RESUMED) == true
    if (!isResumed) return
    navigate(route) { launchSingleTop = true }
}

private fun NavHostController.back() {
    val isResumed = currentBackStackEntry?.lifecycle?.currentState
        ?.isAtLeast(Lifecycle.State.RESUMED) == true
    if (isResumed) navigateUp()
}

/**
 * Home is the hub; every other screen is one level (category detail two) below it. Screens
 * slide in from the right on push and back out on pop, so depth reads spatially.
 */
@Composable
fun FinzyyNavHost(
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    promptSmsConsent: Boolean = false,
    showHomeTour: Boolean = false,
    navController: NavHostController = rememberNavController(),
    startDestination: Destination = Destination.Home
) {
    val slide = tween<androidx.compose.ui.unit.IntOffset>(280)
    var consentShown by rememberSaveable { mutableStateOf(false) }
    var tourPending by rememberSaveable { mutableStateOf(showHomeTour) }
    NavHost(
        navController = navController,
        startDestination = startDestination.route,
        modifier = modifier,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slide) + fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(200)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slide) + fadeOut(tween(200)) }
    ) {
        composable(Destination.Home.route) {
            // First sign-in: open the SMS disclosure once, on top of Home so back lands there.
            LaunchedEffect(Unit) {
                if (promptSmsConsent && !consentShown) {
                    consentShown = true
                    navController.navigate(Destination.SmsConsent.route)
                }
            }
            HomeScreen(
                onSearch = { review -> navController.navigateTo(Destination.search(review)) },
                onAccount = { navController.navigateTo(Destination.Account.route) },
                onAddTransaction = { navController.navigateTo(Destination.AddTransaction.route) },
                onBudget = { navController.navigateTo(Destination.Budget.route) },
                onTrends = { navController.navigateTo(Destination.Trends.route) },
                onCategories = { navController.navigateTo(Destination.Categories.route) },
                onCategory = { navController.navigateTo(Destination.category(it)) },
                onInsights = { navController.navigateTo(Destination.Insights.route) },
                onEnableAutoSync = { navController.navigateTo(Destination.SmsConsent.route) },
                onNotifications = { navController.navigateTo(Destination.Notifications.route) },
                // Starts once Home is actually on screen -- after the first-run SMS consent
                // screen, if that was pushed on top.
                showTour = tourPending,
                onTourDone = { tourPending = false }
            )
        }

        composable(
            Destination.Search.route,
            arguments = listOf(navArgument("review") { type = NavType.BoolType; defaultValue = false })
        ) { entry ->
            SearchScreen(
                onBack = { navController.back() },
                startWithReview = entry.arguments?.getBoolean("review") == true
            )
        }

        composable(Destination.AddTransaction.route) {
            AddTransactionScreen(onBack = { navController.back() })
        }

        composable(Destination.Budget.route) {
            BudgetScreen(onBack = { navController.back() })
        }

        composable(Destination.Trends.route) {
            TrendsScreen(
                onBack = { navController.back() },
                onBudget = { navController.navigateTo(Destination.Budget.route) }
            )
        }

        composable(Destination.Categories.route) {
            CategoriesScreen(
                onBack = { navController.back() },
                onBudget = { navController.navigateTo(Destination.Budget.route) },
                onCategory = { navController.navigateTo(Destination.category(it)) }
            )
        }

        composable(
            Destination.CategoryDetail.route,
            arguments = listOf(navArgument("name") { type = NavType.StringType })
        ) { entry ->
            CategoryDetailScreen(
                category = entry.arguments?.getString("name").orEmpty(),
                onBack = { navController.back() }
            )
        }

        composable(Destination.Notifications.route) {
            NotificationsScreen(
                onBack = { navController.popBackStack() },
                onBudget = { navController.navigateTo(Destination.Budget.route) },
                onCategory = { navController.navigateTo(Destination.category(it)) }
            )
        }

        composable(Destination.Insights.route) {
            InsightsScreen(
                onBack = { navController.back() },
                onBudget = { navController.navigateTo(Destination.Budget.route) },
                onCategory = { navController.navigateTo(Destination.category(it)) }
            )
        }

        composable(Destination.Account.route) {
            AccountScreen(
                onBack = { navController.back() },
                onLogout = onSignedOut,
                onEnableAutoSync = { navController.navigateTo(Destination.SmsConsent.route) },
                onEditProfile = { navController.navigateTo(Destination.EditProfile.route) },
                onAddPhone = { navController.navigateTo(Destination.AddPhone.route) }
            )
        }

        composable(Destination.EditProfile.route) {
            val profile by produceState<UserData?>(null) {
                value = runCatching { RetrofitClient.apiService.getMyProfile().body() }.getOrNull()
            }
            ProfileSetupScreen(
                initial = profile,
                editing = true,
                onSaved = { navController.back() },
                onBack = { navController.back() }
            )
        }

        composable(Destination.AddPhone.route) {
            LinkPhoneScreen(
                onLinked = { navController.back() },
                onSkip = null,
                onBack = { navController.back() }
            )
        }

        composable(Destination.SmsConsent.route) {
            // Every outcome drops the consent screen from the back stack — pressing back
            // from the previous screen must not re-enter the disclosure flow.
            val done = {
                navController.popBackStack()
                Unit
            }
            SmsConsentScreen(
                onBack = { navController.back() },
                onAutoSyncReady = done,
                onManualEntry = done
            )
        }
    }
}
