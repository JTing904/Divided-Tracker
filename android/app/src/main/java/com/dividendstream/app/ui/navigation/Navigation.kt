package com.dividendstream.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"

    const val DASHBOARD = "dashboard"
    const val PORTFOLIO = "portfolio"
    const val CALENDAR = "calendar"
    const val HISTORY = "history"
    const val LEDGER = "ledger"

    const val ADD_STOCK = "add_stock"
    const val PROFILE = "profile"

    const val STOCK_DETAIL_ROUTE = "stock/{symbol}"
    const val STOCK_DETAIL_ARG = "symbol"

    fun stockDetail(symbol: String): String = "stock/$symbol"
}

/** The five destinations in the bottom bar, in order. */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    Home(Routes.DASHBOARD, "Home", Icons.Default.Home),
    Portfolio(Routes.PORTFOLIO, "Portfolio", Icons.Default.PieChart),
    Calendar(Routes.CALENDAR, "Calendar", Icons.Default.CalendarMonth),
    History(Routes.HISTORY, "History", Icons.Default.History),
    Ledger(Routes.LEDGER, "Ledger", Icons.Default.AccountBalanceWallet),
    ;

    companion object {
        fun fromRoute(route: String?): BottomTab? = entries.firstOrNull { it.route == route }
    }
}
