package com.dividendstream.app.ui.ledger

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The named icons a ledger row or a fund can carry.
 *
 * Stored as the [key] string, never as a drawable reference or a URL: the backend keeps a short
 * name it does not interpret, and the client resolves it. That means adding an icon is a client
 * release rather than a migration, and an unknown key from a newer client degrades to a neutral
 * shape on an older one instead of failing to draw.
 *
 * The colours are the point of the exercise as much as the shapes. A wall of identical rows is
 * what makes a ledger feel like homework; a row that is recognisably "food" or "transport" at a
 * glance is what makes it worth opening again tomorrow.
 */
enum class LedgerIcon(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
) {
    // Money coming in.
    Salary("salary", "Salary", Icons.Default.Work, Color(0xFF34D97B)),
    Allowance("allowance", "Allowance", Icons.Default.CardGiftcard, Color(0xFF7BD97F)),
    Business("business", "Business", Icons.Default.Storefront, Color(0xFF52C8A0)),
    Investment("investment", "Investment", Icons.Default.TrendingUp, Color(0xFF4AE88C)),
    Interest("interest", "Interest", Icons.Default.AccountBalance, Color(0xFF3FBF95)),

    // Money going out.
    Food("food", "Food", Icons.Default.Restaurant, Color(0xFFF5A15A)),
    Coffee("coffee", "Coffee", Icons.Default.LocalCafe, Color(0xFFC98A5B)),
    Rent("rent", "Rent", Icons.Default.Home, Color(0xFF7E8CF0)),
    Transport("transport", "Transport", Icons.Default.DirectionsBus, Color(0xFF5AB2F5)),
    Car("car", "Car", Icons.Default.DirectionsCar, Color(0xFF4F9BE8)),
    Bills("bills", "Bills", Icons.Default.Bolt, Color(0xFFF5C451)),
    Phone("phone", "Phone", Icons.Default.PhoneIphone, Color(0xFF9C8CF0)),
    Shopping("shopping", "Shopping", Icons.Default.ShoppingBag, Color(0xFFEF7FA8)),
    Clothes("clothes", "Clothes", Icons.Default.Checkroom, Color(0xFFE08AC0)),
    Health("health", "Health", Icons.Default.LocalHospital, Color(0xFFEF5350)),
    Fun("fun", "Fun", Icons.Default.Movie, Color(0xFFB07BF0)),
    Study("study", "Study", Icons.Default.School, Color(0xFF6FA8F5)),
    Pets("pets", "Pets", Icons.Default.Pets, Color(0xFFD9A05B)),
    Subscription("subscription", "Subscription", Icons.Default.CreditCard, Color(0xFF8E9BAE)),

    // Funds.
    Emergency("emergency", "Emergency", Icons.Default.Shield, Color(0xFF5AB2F5)),
    Savings("savings", "Savings", Icons.Default.Savings, Color(0xFF34D97B)),
    Travel("travel", "Travel", Icons.Default.AirplanemodeActive, Color(0xFF52C8E8)),
    Family("family", "Family", Icons.Default.Favorite, Color(0xFFEF7FA8)),
    Retirement("retirement", "Retirement", Icons.Default.SelfImprovement, Color(0xFFB07BF0)),

    /** The fallback, and what an unrecognised key resolves to. */
    Other("other", "Other", Icons.Default.Category, Color(0xFF8E9BAE)),
    ;

    companion object {
        /**
         * An unknown key is [Other] rather than a crash: a category saved by a newer client, or
         * typed by hand, still has to draw.
         */
        fun of(key: String?): LedgerIcon =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: Other

        /** Offered when adding income. Order is the order they appear in the picker. */
        val income: List<LedgerIcon> = listOf(Salary, Allowance, Business, Investment, Interest, Other)

        /** Offered when adding an outgoing. */
        val expense: List<LedgerIcon> = listOf(
            Food, Coffee, Rent, Transport, Car, Bills, Phone,
            Shopping, Clothes, Health, Fun, Study, Pets, Subscription, Other,
        )

        /** Offered when creating a fund. */
        val fund: List<LedgerIcon> = listOf(
            Emergency, Savings, Travel, Family, Retirement, Investment, Study, Other,
        )
    }
}
