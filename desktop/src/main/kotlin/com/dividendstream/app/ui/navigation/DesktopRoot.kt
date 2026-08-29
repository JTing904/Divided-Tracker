package com.dividendstream.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.dividendstream.app.ui.calendar.CalendarViewModel
import com.dividendstream.app.ui.dashboard.DashboardViewModel
import com.dividendstream.app.ui.dividends.DividendSegment
import com.dividendstream.app.ui.dividends.DividendsScreen
import com.dividendstream.app.ui.home.HomeScreen
import com.dividendstream.app.ui.detail.HoldingDetailScreen
import com.dividendstream.app.ui.detail.HoldingDetailViewModel
import com.dividendstream.app.ui.history.HistoryViewModel
import com.dividendstream.app.ui.ledger.LedgerScreen
import com.dividendstream.app.ui.ledger.LedgerViewModel
import com.dividendstream.app.ui.portfolio.PortfolioScreen
import com.dividendstream.app.ui.portfolio.PortfolioViewModel
import com.dividendstream.app.ui.profile.ProfileScreen
import com.dividendstream.app.ui.profile.ProfileViewModel
import com.dividendstream.app.ui.rememberAppContainer

/**
 * Where the user is. A sealed type rather than route strings: on desktop there is no deep
 * linking and no process death to restore from, so the compiler may as well check that every
 * destination is handled and that the detail screen cannot exist without a symbol.
 */
private sealed interface Destination {
    data object Home : Destination
    data object Dividends : Destination
    data object Portfolio : Destination
    data object Ledger : Destination
    data object AddStock : Destination
    data object Profile : Destination
    data class Detail(val symbol: String) : Destination
}

private fun Destination.tab(): BottomTab? = when (this) {
    Destination.Home -> BottomTab.Home
    Destination.Dividends -> BottomTab.Dividends
    Destination.Portfolio -> BottomTab.Portfolio
    Destination.Ledger -> BottomTab.Ledger
    else -> null
}

private fun BottomTab.destination(): Destination = when (this) {
    BottomTab.Home -> Destination.Home
    BottomTab.Dividends -> Destination.Dividends
    BottomTab.Portfolio -> Destination.Portfolio
    BottomTab.Ledger -> Destination.Ledger
}

/**
 * Minimal back stack. navigation-compose exists for desktop, but it brings a dependency and
 * a string-route indirection to solve problems this window does not have.
 */
private class DesktopNavigator {
    val stack: SnapshotStateList<Destination> = mutableStateListOf(Destination.Home)
    val current: Destination get() = stack.last()

    fun push(destination: Destination) {
        stack.add(destination)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Tab switches reset the stack rather than layering on it, and deliberately do not
     * restore previous state: every tab shows live financial data, so re-entering one should
     * refetch rather than redisplay whatever was on screen last time.
     */
    fun selectTab(tab: BottomTab) {
        stack.clear()
        stack.add(tab.destination())
    }
}

/**
 * Chooses between the splash, the signed-out flow and the signed-in app.
 *
 * The choice is driven by the session store, so an expired refresh token anywhere in the app
 * lands the user back on the login screen without any screen having to handle it.
 */
@Composable
fun DesktopRoot() {
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
private fun AuthFlow(factory: ViewModelProvider.Factory) {
    var showRegister by remember { mutableStateOf(false) }

    // The auth screens are the one place a desktop window is far wider than the design wants:
    // a login form stretched across 1400px looks broken, so it is centred and capped.
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            if (showRegister) {
                val viewModel: RegisterViewModel = viewModel(factory = factory)
                RegisterScreen(viewModel = viewModel, onNavigateToLogin = { showRegister = false })
            } else {
                val viewModel: LoginViewModel = viewModel(factory = factory)
                LoginScreen(viewModel = viewModel, onNavigateToRegister = { showRegister = true })
            }
        }
    }
}

@Composable
private fun SignedInApp(
    container: AppContainer,
    factory: ViewModelProvider.Factory,
    userName: String,
    onSignOut: () -> Unit,
) {
    val navigator = remember { DesktopNavigator() }
    val current = navigator.current

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sidebar(
            selected = current.tab(),
            onSelect = navigator::selectTab,
            onOpenProfile = { navigator.push(Destination.Profile) },
            userName = userName,
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Content is capped and centred: the dashboard's live figure is the focal point,
            // and letting cards run to 2560px would scatter it across the screen.
            //
            // fillMaxWidth() is load-bearing. Without it this box takes its content's
            // intrinsic width rather than the pane's, so it sits narrower than the space
            // available, gets centred, and the screens inside then draw past its right edge.
            Box(Modifier.widthIn(max = 1000.dp).fillMaxWidth().fillMaxHeight()) {
                when (val destination = current) {
                    Destination.Home -> {
                        val viewModel: DashboardViewModel = viewModel(factory = factory)
                        RefreshOnEnter(viewModel::refresh)
                        HomeScreen(
                            viewModel = viewModel,
                            userName = userName,
                            onOpenDividends = { navigator.selectTab(BottomTab.Dividends) },
                            onOpenLedger = { navigator.selectTab(BottomTab.Ledger) },
                        )
                    }

                    Destination.Dividends -> {
                        val dashboard: DashboardViewModel = viewModel(factory = factory)
                        val calendar: CalendarViewModel = viewModel(factory = factory)
                        val history: HistoryViewModel = viewModel(factory = factory)
                        var segment by remember { mutableStateOf(DividendSegment.Live) }

                        // One store for the whole window means these ViewModels outlive the
                        // destination, so entering the tab has to refetch. Keyed on the segment
                        // as well, so switching to the calendar reloads the calendar.
                        LaunchedEffect(segment) {
                            when (segment) {
                                DividendSegment.Live -> dashboard.refresh()
                                DividendSegment.Calendar -> calendar.refresh()
                                DividendSegment.History -> history.refresh()
                            }
                        }

                        DividendsScreen(
                            segment = segment,
                            onSegmentChange = { segment = it },
                            dashboardViewModel = dashboard,
                            calendarViewModel = calendar,
                            historyViewModel = history,
                                userName = userName,
                            onAddStock = { navigator.push(Destination.AddStock) },
                            onOpenStock = { navigator.push(Destination.Detail(it)) },
                        )
                    }

                    Destination.Portfolio -> {
                        val viewModel: PortfolioViewModel = viewModel(factory = factory)
                        RefreshOnEnter(viewModel::refresh)
                        PortfolioScreen(
                            viewModel = viewModel,
                            serverClock = container.serverClock,
                            onAddStock = { navigator.push(Destination.AddStock) },
                            onOpenStock = { navigator.push(Destination.Detail(it)) },
                        )
                    }

                    Destination.Ledger -> {
                        val viewModel: LedgerViewModel = viewModel(factory = factory)
                        RefreshOnEnter(viewModel::refresh)
                        LedgerScreen(viewModel = viewModel)
                    }

                    Destination.Profile -> {
                        val viewModel: ProfileViewModel = viewModel(factory = factory)
                        ProfileScreen(
                            viewModel = viewModel,
                            onBack = navigator::pop,
                            onSignOut = onSignOut,
                        )
                    }

                    Destination.AddStock -> {
                        val viewModel: AddStockViewModel = viewModel(factory = factory)
                        AddStockScreen(
                            viewModel = viewModel,
                            onBack = navigator::pop,
                            onSaved = {
                                // Land on the portfolio so the new position is visible immediately.
                                navigator.selectTab(BottomTab.Portfolio)
                            },
                        )
                    }

                    is Destination.Detail -> {
                        // Keyed so switching between two stocks builds a fresh ViewModel
                        // rather than reusing the first symbol's state.
                        val viewModel: HoldingDetailViewModel = viewModel(
                            key = destination.symbol,
                            factory = AppViewModelProvider.holdingDetailFactory(
                                container, destination.symbol,
                            ),
                        )
                        HoldingDetailScreen(viewModel = viewModel, onBack = navigator::pop)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    selected: BottomTab?,
    onSelect: (BottomTab) -> Unit,
    onOpenProfile: () -> Unit,
    userName: String,
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 20.dp),
    ) {
        Text(
            "Dividend Stream",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            userName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(28.dp))

        BottomTab.entries.forEach { tab ->
            SidebarItem(
                tab = tab,
                selected = selected == tab,
                onClick = { onSelect(tab) },
            )
        }

        Spacer(Modifier.weight(1f))

        // Sign out lives inside the profile screen now, alongside everything else about the
        // person -- it was the only thing down here, which gave the one irreversible action in
        // the app a permanent button of its own.
        TextButton(onClick = onOpenProfile, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("You", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SidebarItem(tab: BottomTab, selected: Boolean, onClick: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val tint =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = { Icon(tab.icon, contentDescription = tab.label) },
            label = { Text(tab.label) },
            colors = NavigationRailItemDefaults.colors(
                selectedIconColor = tint,
                selectedTextColor = tint,
                indicatorColor = background,
                unselectedIconColor = tint,
                unselectedTextColor = tint,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Refetches when a destination is entered.
 *
 * navigation-compose gives every back-stack entry its own ViewModelStore, so on Android each
 * tab visit builds a fresh ViewModel that loads in its initialiser. This navigator has one
 * store for the whole window, so `viewModel()` returns the same instance for the lifetime of
 * the app — without this, a portfolio loaded before the user added a stock would keep showing
 * the old list forever.
 *
 * Keyed on Unit and therefore re-run whenever the screen re-enters composition, which is
 * exactly a tab switch. Every tab shows live financial data, so re-entering one should
 * refetch rather than redisplay whatever was on screen last time.
 */
@Composable
private fun RefreshOnEnter(refresh: () -> Unit) {
    LaunchedEffect(Unit) { refresh() }
}
