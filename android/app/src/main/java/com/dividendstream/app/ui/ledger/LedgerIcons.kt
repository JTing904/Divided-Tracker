package com.dividendstream.app.ui.ledger

import androidx.compose.ui.graphics.Color

/**
 * The picture a ledger row or a fund carries.
 *
 * Emoji rather than vector icons, and rather than downloaded artwork. The reasons are worth
 * writing down, because "just use images" looks like the obvious answer and is not:
 *
 * - **Licensing.** Free icon sets almost all require attribution or forbid commercial use.
 *   Emoji are supplied by the operating system and carry no such condition.
 * - **The desktop build compiles the Android sources.** An Android drawable does not exist
 *   there, so any bundled artwork would have to be duplicated per platform and kept in step.
 *   A string of text renders identically in both.
 * - **Size.** Thirty colour images is a megabyte in an APK that is already twenty; the whole
 *   of this file is a few hundred bytes.
 *
 * The [key] is what is stored -- `"food"`, never the emoji itself. Storing the character would
 * make the database depend on a Unicode version, and an unknown key from a newer client
 * degrades to [Other] instead of drawing a box.
 *
 * [tint] is no longer a colour for the glyph, which supplies its own. It is the wash behind it
 * and the colour of that row's figures, chosen to sit with the emoji rather than fight it.
 */
enum class LedgerIcon(
    val key: String,
    val label: String,
    val emoji: String,
    val tint: Color,
) {
    // Money coming in.
    Salary("salary", "Salary", "💼", Color(0xFF34D97B)),
    Allowance("allowance", "Allowance", "🎁", Color(0xFF7BD97F)),
    Business("business", "Business", "🏪", Color(0xFF52C8A0)),
    Investment("investment", "Investment", "📈", Color(0xFF4AE88C)),
    Interest("interest", "Interest", "🏦", Color(0xFF3FBF95)),
    Bonus("bonus", "Bonus", "🎉", Color(0xFF6FD9A8)),

    // Money going out.
    Food("food", "Food", "🍜", Color(0xFFF5A15A)),
    Coffee("coffee", "Coffee", "☕", Color(0xFFC98A5B)),
    Groceries("groceries", "Groceries", "🛒", Color(0xFFE8A85A)),
    Rent("rent", "Rent", "🏠", Color(0xFF7E8CF0)),
    Transport("transport", "Transport", "🚌", Color(0xFF5AB2F5)),
    Car("car", "Car", "🚗", Color(0xFF4F9BE8)),
    Fuel("fuel", "Fuel", "⛽", Color(0xFF6EA8D8)),
    Bills("bills", "Bills", "⚡", Color(0xFFF5C451)),
    Phone("phone", "Phone", "📱", Color(0xFF9C8CF0)),
    Shopping("shopping", "Shopping", "🛍️", Color(0xFFEF7FA8)),
    Clothes("clothes", "Clothes", "👕", Color(0xFFE08AC0)),
    Health("health", "Health", "💊", Color(0xFFEF5350)),
    Fun("fun", "Fun", "🎬", Color(0xFFB07BF0)),
    Study("study", "Study", "🎓", Color(0xFF6FA8F5)),
    Pets("pets", "Pets", "🐾", Color(0xFFD9A05B)),
    Subscription("subscription", "Subscription", "💳", Color(0xFF8E9BAE)),
    Gift("gift", "Gift", "💝", Color(0xFFEF8FB8)),

    // Funds.
    Emergency("emergency", "Emergency", "🛡️", Color(0xFF5AB2F5)),
    Savings("savings", "Savings", "🐷", Color(0xFF34D97B)),
    Travel("travel", "Travel", "✈️", Color(0xFF52C8E8)),
    Family("family", "Family", "❤️", Color(0xFFEF7FA8)),
    Retirement("retirement", "Retirement", "🌴", Color(0xFFB07BF0)),
    House("house", "House", "🏡", Color(0xFF8CA8F0)),

    /** The fallback, and what an unrecognised key resolves to. */
    Other("other", "Other", "📦", Color(0xFF8E9BAE)),
    ;

    companion object {
        /**
         * An unknown key is [Other] rather than a crash: a category saved by a newer client, or
         * typed by hand, still has to draw.
         */
        fun of(key: String?): LedgerIcon =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: Other

        /** Offered when adding income. Order is the order they appear in the picker. */
        val income: List<LedgerIcon> =
            listOf(Salary, Allowance, Business, Investment, Interest, Bonus, Other)

        /** Offered when adding an outgoing. */
        val expense: List<LedgerIcon> = listOf(
            Food, Coffee, Groceries, Rent, Transport, Car, Fuel, Bills, Phone,
            Shopping, Clothes, Health, Fun, Study, Pets, Subscription, Gift, Other,
        )

        /** Offered when creating a fund. */
        val fund: List<LedgerIcon> = listOf(
            Emergency, Savings, Travel, Family, House, Retirement, Investment, Study, Other,
        )
    }
}
