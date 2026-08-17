package com.dividendstream.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dividendstream.app.AppContainer
import com.dividendstream.app.ui.AppViewModelProvider
import com.dividendstream.app.ui.SessionState
import com.dividendstream.app.ui.SessionViewModel
import com.dividendstream.app.ui.SplashScreen
import com.dividendstream.app.ui.addstock.AddStockScreen
import com.dividendstream.app.ui.addstock.AddStockViewModel
import com.dividendstream.app.ui.auth.LoginScreen
import com.dividendstream.app.ui.auth.LoginViewModel
import com.dividendstream.app.ui.auth.RegisterScreen
import com.dividendstream.app.ui.auth.RegisterViewModel
import com.dividendstream.app.ui.calendar.CalendarScreen
import com.dividendstream.app.ui.calendar.CalendarViewModel
import com.dividendstream.app.ui.dashboard.DashboardScreen
import com.dividendstream.app.ui.dashboard.DashboardViewModel
import com.dividendstream.app.ui.detail.HoldingDetailScreen
import com.dividendstream.app.ui.detail.HoldingDetailViewModel
import com.dividendstream.app.ui.history.HistoryScreen
import com.dividendstream.app.ui.history.HistoryViewModel
import com.dividendstream.app.ui.portfolio.PortfolioScreen
import com.dividendstream.app.ui.portfolio.PortfolioViewModel
import com.dividendstream.app.ui.rememberAppContainer

/**
 * Chooses between the splash, the signed-out flow and the signed-in app.
 *
 * The choice is driven by the session store, so an expired refresh token anywhere in the
 * app lands the user back on the login screen without any screen having to handle it.
 */
@Composable
fun DividendStreamRoot() {
    val container = rememberAppContainer()
    val factory = remember(container) { AppViewModelProvider.factory(container) }
    val sessionViewModel: SessionViewModel = viewModel(factory = factory)
    val sessionState by sessionViewModel.state.collectAsStateWithLifecycle()

    when (val session = sessionState) {
        SessionState.Loading -> SplashScreen()

        SessionState.SignedOut -> AuthFlow(factory = factory)

        is SessionState.SignedIn -> SignedInApp(
            container = container,
            factory = factory,
            userName = session.session.userName,
            onSignOut = sessionViewModel::signOut,
        )
    }
}

@Composable
private fun AuthFlow(factory: androidx.lifecycle.ViewModelProvider.Factory) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            val viewModel: LoginViewModel = viewModel(factory = factory)
            LoginScreen(
                viewModel = viewModel,
                onNavigateToRegister = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            val viewModel: RegisterViewModel = viewModel(factory = factory)
            RegisterScreen(
                viewModel = viewModel,
                onNavigateToLogin = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInApp(
    container: AppContainer,
    factory: androidx.lifecycle.ViewModelProvider.Factory,
    userName: String,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = BottomTab.fromRoute(currentRoute)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Full-screen destinations bring their own app bar.
            if (currentTab != null) {
                TopAppBar(
                    title = {
                        Text(
                            "Dividend Stream",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    actions = {
                        IconButton(onClick = onSignOut) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign out",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = { navController.navigateToTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.DASHBOARD) {
                val viewModel: DashboardViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsStateWithLifecycle()
                PullToRefresh(state.isRefreshing, { viewModel.refresh(fromPull = true) }) {
                    DashboardScreen(
                        viewModel = viewModel,
                        userName = userName,
                        onAddStock = { navController.navigate(Routes.ADD_STOCK) },
                        onOpenStock = { symbol -> navController.navigate(Routes.stockDetail(symbol)) },
                    )
                }
            }

            composable(Routes.PORTFOLIO) {
                val viewModel: PortfolioViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsStateWithLifecycle()
                PullToRefresh(state.isRefreshing, { viewModel.refresh(fromPull = true) }) {
                    PortfolioScreen(
                        viewModel = viewModel,
                        serverClock = container.serverClock,
                        onAddStock = { navController.navigate(Routes.ADD_STOCK) },
                        onOpenStock = { symbol -> navController.navigate(Routes.stockDetail(symbol)) },
                    )
                }
            }

            composable(Routes.CALENDAR) {
                val viewModel: CalendarViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsStateWithLifecycle()
                PullToRefresh(state.isRefreshing, { viewModel.refresh(fromPull = true) }) {
                    CalendarScreen(
                        viewModel = viewModel,
                        onOpenStock = { symbol -> navController.navigate(Routes.stockDetail(symbol)) },
                    )
                }
            }

            composable(Routes.HISTORY) {
                val viewModel: HistoryViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsStateWithLifecycle()
                PullToRefresh(state.isRefreshing, { viewModel.refresh(fromPull = true) }) {
                    HistoryScreen(viewModel = viewModel)
                }
            }

            composable(Routes.ADD_STOCK) {
                val viewModel: AddStockViewModel = viewModel(factory = factory)
                AddStockScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        // Land on the portfolio so the new position is visible immediately.
                        navController.popBackStack()
                        navController.navigateToTab(BottomTab.Portfolio)
                    },
                )
            }

            composable(
                route = Routes.STOCK_DETAIL_ROUTE,
                arguments = listOf(navArgument(Routes.STOCK_DETAIL_ARG) { type = NavType.StringType }),
            ) { entry ->
                val symbol = entry.arguments?.getString(Routes.STOCK_DETAIL_ARG).orEmpty()
                val viewModel: HoldingDetailViewModel = viewModel(
                    factory = AppViewModelProvider.holdingDetailFactory(container, symbol),
                )
                HoldingDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Wraps a tab in the pull-to-refresh gesture.
 *
 * It sits here, at the Android-only navigation layer, rather than inside the screens: the
 * desktop build compiles those same screen files, and a pull gesture means nothing to a
 * mouse. The cost is collecting the tab's state twice — once here for the spinner, once in
 * the screen for its content — which is a second collector on the same StateFlow, not a
 * second fetch.
 *
 * The gesture rides on nested scroll, so it is available wherever the tab has a scrolling
 * list. A tab that failed to load its first page has nothing to scroll; that case is served
 * by the retry button on the error banner instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}

/**
 * Tab switches deliberately do not restore saved state: every tab shows live financial data,
 * and re-entering one should refetch rather than redisplay whatever was on screen last time.
 */
private fun NavHostController.navigateToTab(tab: BottomTab) {
    navigate(tab.route) {
        popUpTo(Routes.DASHBOARD) { inclusive = tab.route == Routes.DASHBOARD }
        launchSingleTop = true
    }
}
