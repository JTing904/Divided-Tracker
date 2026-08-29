package com.dividendstream.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"

    const val HOME = "home"
    const val DIVIDENDS = "dividends"
    const val PORTFOLIO = "portfolio"
    const val LEDGER = "ledger"

    const val ADD_STOCK = "add_stock"
    const val PROFILE = "profile"

    const val STOCK_DETAIL_ROUTE = "stock/{symbol}"
    const val STOCK_DETAIL_ARG = "symbol"

    fun stockDetail(symbol: String): String = "stock/$symbol"
}

/**
 * The four destinations in the bottom bar, in order.
 *
 * Four rather than six. The calendar and the history are two further views of the dividends
 * already on the Dividends tab, so they live inside it: putting three of the app's top-level
 * slots on one subject left no room for anything that was not about dividends, and a
 * phone-width bar with six labels truncates every one of them.
 */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    Home(Routes.HOME, "Home", Icons.Default.Home),
    Dividends(Routes.DIVIDENDS, "Dividends", Icons.Default.TrendingUp),
    Portfolio(Routes.PORTFOLIO, "Portfolio", Icons.Default.PieChart),
    Ledger(Routes.LEDGER, "Ledger", Icons.Default.AccountBalanceWallet),
    ;

    companion object {
        fun fromRoute(route: String?): BottomTab? = entries.firstOrNull { it.route == route }
    }
}
