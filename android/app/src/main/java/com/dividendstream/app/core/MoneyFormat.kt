package com.dividendstream.app.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many decimals each kind of figure is shown with. */
object Precision {
    /** Settled and expected amounts: RM320.00. */
    const val AMOUNT = 2

    /** The live counter: RM128.478511. Enough digits that movement is visible every frame. */
    const val LIVE = 6

    /** Per-second and per-minute rates: RM0.00002058. */
    const val RATE = 8
}

fun currencySymbol(code: String): String = when (code.uppercase()) {
    "MYR" -> "RM"
    "USD" -> "$"
    "SGD" -> "S$"
    "EUR" -> "€"
    "GBP" -> "£"
    else -> code.uppercase()
}

/**
 * Formats an amount for display.
 *
 * Always rounds DOWN: this is shown against unpaid, still-accumulating money, and rounding
 * a running estimate up would overstate it.
 */
fun BigDecimal.formatAmount(decimals: Int = Precision.AMOUNT, grouping: Boolean = true): String =
    DecimalFormat().apply {
        decimalFormatSymbols = DecimalFormatSymbols(Locale.US)
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
        isGroupingUsed = grouping
        roundingMode = RoundingMode.DOWN
    }.format(this)

/** e.g. `RM320.00`. */
fun BigDecimal.formatMoney(currency: String, decimals: Int = Precision.AMOUNT): String =
    "${currencySymbol(currency)}${formatAmount(decimals)}"

/** Share counts drop meaningless trailing zeros: `1,000` rather than `1,000.0000`. */
fun BigDecimal.formatShares(): String =
    stripTrailingZeros().let { value ->
        val decimals = maxOf(value.scale(), 0).coerceAtMost(4)
        value.formatAmount(decimals)
    }

fun BigDecimal.formatPercent(decimals: Int = 2): String = "${formatAmount(decimals, grouping = false)}%"

private val dayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val monthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val dayOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("dd", Locale.US)
private val monthShort: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)

fun LocalDate.formatFull(): String = format(dayMonthYear)

/** Year omitted, for rows where the month heading already supplies it. */
fun LocalDate.formatDayMonth(): String = format(dayMonth)
fun LocalDate.formatMonthYear(): String = format(monthYear).uppercase(Locale.US)
fun LocalDate.formatDayNumber(): String = format(dayOnly)
fun LocalDate.formatMonthAbbrev(): String = format(monthShort).uppercase(Locale.US)

/** `14d 08h 22m`, or `04h 23m` once inside a day. Used for payment countdowns. */
fun formatCountdown(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "due now"
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        days > 0 -> "%dd %02dh %02dm".format(days, hours, minutes)
        hours > 0 -> "%02dh %02dm".format(hours, minutes)
        else -> "%02dm".format(minutes)
    }
}
