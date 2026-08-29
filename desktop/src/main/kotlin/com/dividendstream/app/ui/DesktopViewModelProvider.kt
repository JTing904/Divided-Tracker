package com.dividendstream.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dividendstream.app.AppContainer
import com.dividendstream.app.BuildConfig
import com.dividendstream.app.ui.addstock.AddStockViewModel
import com.dividendstream.app.ui.auth.LoginViewModel
import com.dividendstream.app.ui.auth.RegisterViewModel
import com.dividendstream.app.ui.calendar.CalendarViewModel
import com.dividendstream.app.ui.dashboard.DashboardViewModel
import com.dividendstream.app.ui.detail.HoldingDetailViewModel
import com.dividendstream.app.ui.history.HistoryViewModel
import com.dividendstream.app.ui.ledger.LedgerViewModel
import com.dividendstream.app.ui.portfolio.PortfolioViewModel
import com.dividendstream.app.ui.profile.ProfileViewModel

/**
 * There is no Application object to hang the container off on desktop, so it is provided
 * through the composition instead. Same accessor name as on Android, so the screens do not
 * care which one they are running under.
 */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> =
    staticCompositionLocalOf { error("LocalAppContainer was read before it was provided") }

@Composable
fun rememberAppContainer(): AppContainer = LocalAppContainer.current

/**
 * Constructs every ViewModel from the container.
 *
 * Keeping construction in one place means each ViewModel takes plain constructor arguments
 * and can be built with fakes in a unit test, with no framework involvement.
 */
object AppViewModelProvider {

    fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer { SessionViewModel(container.authRepository) }
        initializer { LoginViewModel(container.authRepository) }
        initializer { RegisterViewModel(container.authRepository) }
        initializer {
            DashboardViewModel(
                container.dividendRepository,
                container.ledgerRepository,
                container.portfolioRepository,
                container.appInfoRepository,
                container.serverClock,
            )
        }
        initializer { PortfolioViewModel(container.portfolioRepository, container.purchaseQueue) }
        initializer {
            AddStockViewModel(
                container.portfolioRepository,
                container.purchaseQueue,
                container.serverAvailability,
            )
        }
        initializer { CalendarViewModel(container.dividendRepository, container.serverClock) }
        initializer { HistoryViewModel(container.dividendRepository) }
        initializer { LedgerViewModel(container.ledgerRepository, container.serverClock) }
        initializer {
            ProfileViewModel(
                container.authRepository,
                container.appInfoRepository,
                container.settingsStore,
                BuildConfig.VERSION_NAME,
            )
        }
    }

    /** Detail needs a symbol, so it gets its own factory per destination. */
    fun holdingDetailFactory(container: AppContainer, symbol: String): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                HoldingDetailViewModel(
                    symbol = symbol,
                    portfolioRepository = container.portfolioRepository,
                    dividendRepository = container.dividendRepository,
                    serverClock = container.serverClock,
                )
            }
        }
}
