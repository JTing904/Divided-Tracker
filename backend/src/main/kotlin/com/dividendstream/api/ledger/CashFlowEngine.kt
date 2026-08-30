package com.dividendstream.api.ledger

import com.dividendstream.api.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A half-open interval `[start, end)`. */
data class Window(val start: Instant, val end: Instant) {
    val seconds: Long get() = Duration.between(start, end).seconds
    val isEmpty: Boolean get() = seconds <= 0L
}

/**
 * What one declared flow contributes to the current month.
 *
 * [window] is null when the flow is not live at any point in the month -- it started later, or
 * it has already ended. Such a flow still has a rate worth showing on its own row; it just
 * contributes nothing to the totals.
 */
data class FlowProjection(
    val ratePerSecond: BigDecimal,
    val window: Window?,
    val expected: BigDecimal,
    val accrued: BigDecimal,
    /**
     * What has actually arrived: whole periods that have finished, and nothing else.
     *
     * [accrued] answers "at this rate, how far through the month are we", which is the right
     * question for a dividend -- a holding earns by the day whether or not anything has been
     * paid. It is the wrong question for a wage. RM3,000 a month is not RM1,000 by the tenth;
     * it is nothing until it lands and then all of it, and a fund filled from [accrued] sets
     * aside money nobody has yet, which is how a person ends up spending it.
     */
    val received: BigDecimal,
)

/**
 * A per-second rate restated over the horizons a person thinks in, measured on the real
 * calendar. Includes a week, which the dividend engine's equivalent has no use for and a
 * budget very much does.
 */
data class LedgerRateBreakdown(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perWeek: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)

/**
 * The ledger maths. Pure by design, like [com.dividendstream.api.dividend.DividendAccumulationEngine].
 *
 * Nothing here reads a clock, a database or a request. That is what lets the same formula run
 * on the backend and, independently, on the client between refreshes, so the counter never
 * jumps when a response lands. It is also why no row is written per second: the database
 * stores the *parameters* -- amount, period, dates -- and the figure is derived on demand.
 *
 * Two decisions here are worth stating plainly, because everything else follows from them.
 *
 * **A rate comes from the flow's own period, measured on the real calendar.** RM3,000 a month
 * is divided by the seconds in *this* month, not by an average month, so the counter lands on
 * exactly RM3,000 at midnight on the last day -- in February as well as in July. The same
 * holds for a yearly figure in a leap year.
 *
 * **Every figure on the screen is measured over one calendar month**, whatever mixture of
 * periods the flows use. A daily allowance contributes its rate times the seconds elapsed this
 * month; a monthly salary contributes its own. They are rates, so they simply add.
 */
object CashFlowEngine {

    /** The month containing [at], in [zone]. Every ledger figure is measured over this. */
    fun monthWindow(at: Instant, zone: ZoneId): Window {
        val date = LocalDate.ofInstant(at, zone)
        val first = date.withDayOfMonth(1)
        return Window(
            start = first.atStartOfDay(zone).toInstant(),
            end = first.plusMonths(1).atStartOfDay(zone).toInstant(),
        )
    }

    /**
     * Length of the [period] that contains [at], on the real calendar.
     *
     * Measured rather than assumed: a day is not always 86,400 seconds in a zone that observes
     * daylight saving, and a month is anything from 28 to 31 days. Dividing by a nominal length
     * would leave the counter finishing the period slightly above or below the declared amount,
     * which is the one thing a figure labelled "RM3,000 a month" must not do.
     */
    fun periodSeconds(period: CashFlowPeriod, at: Instant, zone: ZoneId): Long {
        val date = LocalDate.ofInstant(at, zone)
        val (from, to) = when (period) {
            CashFlowPeriod.DAILY -> date to date.plusDays(1)
            CashFlowPeriod.WEEKLY -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                monday to monday.plusWeeks(1)
            }
            CashFlowPeriod.MONTHLY -> date.withDayOfMonth(1) to date.withDayOfMonth(1).plusMonths(1)
            CashFlowPeriod.YEARLY -> date.withDayOfYear(1) to date.withDayOfYear(1).plusYears(1)
        }
        return Duration.between(from.atStartOfDay(zone), to.atStartOfDay(zone)).seconds
    }

    /**
     * What [amount] per [period] works out to per second.
     *
     * Held at [Money.RATE_SCALE] (12dp) for the same reason a dividend rate is: RM3,000 a month
     * is about RM0.001140795 per second, and it is multiplied by a large elapsed-second count
     * before anyone reads it. Rounding to cents here would be wrong by ringgit within a day.
     */
    fun ratePerSecond(amount: BigDecimal, period: CashFlowPeriod, at: Instant, zone: ZoneId): BigDecimal {
        require(amount.signum() >= 0) { "amount must not be negative" }
        return amount.divide(
            BigDecimal.valueOf(periodSeconds(period, at, zone)),
            Money.RATE_SCALE,
            RoundingMode.HALF_UP,
        )
    }

    /**
     * The part of [month] during which a flow running from [startsOn] to [endsOn] is live,
     * or null when it is not live in that month at all.
     *
     * This is what makes a job started on the 15th show half a month's pay rather than a full
     * one. [endsOn] is inclusive -- the last day a flow applies -- so the window runs to the
     * start of the day after it.
     */
    fun activeWindow(month: Window, startsOn: LocalDate, endsOn: LocalDate?, zone: ZoneId): Window? {
        val from = maxOf(month.start, startsOn.atStartOfDay(zone).toInstant())
        val to = endsOn
            ?.let { minOf(month.end, it.plusDays(1).atStartOfDay(zone).toInstant()) }
            ?: month.end
        val window = Window(from, to)
        return if (window.isEmpty) null else window
    }

    /**
     * What [amount] per period comes to across [window].
     *
     * Derived from the declared amount, deliberately -- **not** from the per-second rate
     * multiplied back out. The rate is already rounded to 12dp, and multiplying it by 2.6
     * million seconds magnifies that rounding into roughly a thousandth of a cent: a salary
     * declared as RM3,000 a month would finish the month reading RM3,000.0000011 in July and
     * RM2,999.9999998 in February. Small, but there is no reason to accept it, and the figure
     * the person typed in is the one they are entitled to see the counter land on.
     *
     * Multiplied before dividing so the ratio of window to period stays exact.
     */
    fun expectedOver(amount: BigDecimal, periodSeconds: Long, window: Window): BigDecimal {
        require(periodSeconds > 0) { "periodSeconds must be positive" }
        if (window.isEmpty) return Money.ZERO_ACCRUAL
        return Money.accrual(
            amount.multiply(BigDecimal.valueOf(window.seconds))
                .divide(BigDecimal.valueOf(periodSeconds), Money.ACCRUAL_SCALE, RoundingMode.DOWN),
        )
    }

    /**
     * Value accrued by [at], clamped to `[0, expected]` and to the window at both ends.
     *
     * Identical in shape to the dividend engine's accrual, and deliberately so: the client runs
     * one calculator for both, which is what guarantees the two agree. The clamp against
     * [expected] is what keeps the running figure honest in the final second, where the rate
     * multiplied out would otherwise creep just past the declared amount.
     */
    fun accruedAt(
        ratePerSecond: BigDecimal,
        expected: BigDecimal,
        window: Window,
        at: Instant,
    ): BigDecimal {
        if (ratePerSecond.signum() <= 0 || expected.signum() <= 0) return Money.ZERO_ACCRUAL
        if (!at.isAfter(window.start)) return Money.ZERO_ACCRUAL
        if (!at.isBefore(window.end)) return Money.accrual(expected)

        val elapsed = Duration.between(window.start, at).seconds
        return Money.accrual(ratePerSecond.multiply(BigDecimal.valueOf(elapsed)).min(expected))
    }

    /**
     * What a flow has actually paid out inside [window], at [at].
     *
     * Money arrives in lumps on a date, not by the second. A period's amount lands when that
     * period **finishes**: a daily allowance of RM20 is RM20 once the day is over, and RM0
     * for the whole of the day you are living through. Whole periods only -- half a week is
     * not half a payment, it is no payment.
     *
     * That is deliberately the cautious end of the choice. A wage paid on the 25th reads as
     * nothing until the month closes, so this can be *behind* what someone actually holds. It
     * is never ahead of it, and only one of those two errors gets a person to spend money they
     * do not have.
     *
     * A period counts only if the flow was live for all of it. A daily allowance beginning
     * halfway through Tuesday does not pay for Tuesday.
     */
    fun receivedOver(
        amount: BigDecimal,
        period: CashFlowPeriod,
        startsOn: LocalDate,
        endsOn: LocalDate?,
        window: Window,
        at: Instant,
        zone: ZoneId,
        arrivesOn: Int? = null,
        arrivesMonth: Int? = null,
    ): BigDecimal {
        if (amount.signum() <= 0 || window.isEmpty) return Money.ZERO_AMOUNT
        // A payment that arrives after now, or after the window closes, has not landed in it.
        val until = minOf(at, window.end)
        if (!until.isAfter(window.start)) return Money.ZERO_AMOUNT

        // Start from the period the window opens inside, which may have begun before it: a
        // week running across a month end pays on a day in the new month.
        var opens = previousBoundary(LocalDate.ofInstant(window.start, zone), period)
        var paid = 0L
        var guard = 0
        while (guard++ < MAX_BOUNDARIES) {
            val closes = nextBoundary(opens, period)
            val lastDay = closes.minusDays(1)
            val payday = paydayOf(opens, lastDay, period, arrivesOn, arrivesMonth)
            val landsAt = payday.atStartOfDay(zone).toInstant()

            if (landsAt.isAfter(until)) break
            if (landsAt.isAfter(window.start) || landsAt == window.start) {
                // Live on the day the money lands. A named payday is a fact about a date, so
                // being employed on it is what matters; without one the payment closes the
                // period, and the day that earned it is the period's last.
                val onDay = if (arrivesOn == null) lastDay else payday
                if (!onDay.isBefore(startsOn) && (endsOn == null || !onDay.isAfter(endsOn))) paid++
            }
            opens = closes
        }
        return Money.amount(amount.multiply(BigDecimal.valueOf(paid)))
    }

    /**
     * The day one period's money arrives on.
     *
     * With no named day the money closes the period: it lands at the start of the day after
     * the last one, which is the same instant the period ends. A named day is taken at its
     * word and lands at the start of it -- somebody who says they are paid on the 28th has
     * the money on the 28th, and shading that to the 29th to be safe would be second-guessing
     * the one person who knows.
     */
    private fun paydayOf(
        opens: LocalDate,
        lastDay: LocalDate,
        period: CashFlowPeriod,
        arrivesOn: Int?,
        arrivesMonth: Int?,
    ): LocalDate {
        if (arrivesOn == null) return lastDay.plusDays(1)
        return when (period) {
            CashFlowPeriod.WEEKLY -> {
                val target = arrivesOn.coerceIn(1, 7)
                opens.plusDays((target - opens.dayOfWeek.value).toLong().coerceAtLeast(0L))
            }
            // February has no 31st, and a wage paid "on the 31st" is paid on the last day.
            CashFlowPeriod.MONTHLY -> opens.withDayOfMonth(arrivesOn.coerceIn(1, opens.lengthOfMonth()))
            // A year needs both halves of a date. Given only a day there is no month to put it
            // in, so it falls back to closing the period rather than guessing January.
            CashFlowPeriod.YEARLY -> {
                if (arrivesMonth == null) return lastDay.plusDays(1)
                val inMonth = opens.withMonth(arrivesMonth.coerceIn(1, 12))
                inMonth.withDayOfMonth(arrivesOn.coerceIn(1, inMonth.lengthOfMonth()))
            }
            // A day cannot pay on some other day.
            CashFlowPeriod.DAILY -> lastDay.plusDays(1)
        }
    }

    /** The day the period containing [day] began -- or [day] itself if it begins one. */
    private fun previousBoundary(day: LocalDate, period: CashFlowPeriod): LocalDate = when (period) {
        CashFlowPeriod.DAILY -> day
        CashFlowPeriod.WEEKLY -> day.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        CashFlowPeriod.MONTHLY -> day.withDayOfMonth(1)
        CashFlowPeriod.YEARLY -> day.withDayOfYear(1)
    }

    private fun nextBoundary(day: LocalDate, period: CashFlowPeriod): LocalDate = when (period) {
        CashFlowPeriod.DAILY -> day.plusDays(1)
        CashFlowPeriod.WEEKLY -> day.plusWeeks(1)
        CashFlowPeriod.MONTHLY -> day.plusMonths(1)
        CashFlowPeriod.YEARLY -> day.plusYears(1)
    }

    /** A month of days, a year of months: no window this engine serves needs more. */
    private const val MAX_BOUNDARIES = 400

    /**
     * Everything one declared flow contributes to the current month, in one call.
     *
     * Composed here rather than at the call site because the order matters: the expected total
     * has to come from the amount and the window while the running figure comes from the rate,
     * and getting that backwards is exactly the drift [expectedOver] exists to avoid.
     */
    fun project(
        amount: BigDecimal,
        period: CashFlowPeriod,
        startsOn: LocalDate,
        endsOn: LocalDate?,
        month: Window,
        at: Instant,
        zone: ZoneId,
        arrivesOn: Int? = null,
        arrivesMonth: Int? = null,
    ): FlowProjection {
        val rate = ratePerSecond(amount, period, at, zone)
        val window = activeWindow(month, startsOn, endsOn, zone)
            ?: return FlowProjection(
                rate, null, Money.ZERO_ACCRUAL, Money.ZERO_ACCRUAL, Money.ZERO_AMOUNT,
            )

        val expected = expectedOver(amount, periodSeconds(period, at, zone), window)
        return FlowProjection(
            ratePerSecond = rate,
            window = window,
            expected = expected,
            accrued = accruedAt(rate, expected, window, at),
            received = receivedOver(
                amount, period, startsOn, endsOn, month, at, zone, arrivesOn, arrivesMonth,
            ),
        )
    }

    /** Fraction of [window] elapsed at [at], in `[0, 1]`. Display only; carries no money. */
    fun progressAt(window: Window, at: Instant): BigDecimal {
        if (window.isEmpty) return BigDecimal.ONE
        val elapsed = Duration.between(window.start, at).seconds.coerceIn(0L, window.seconds)
        return BigDecimal.valueOf(elapsed)
            .divide(BigDecimal.valueOf(window.seconds), 6, RoundingMode.DOWN)
    }

    /** The calendar day containing [at], in [zone]. */
    fun dayWindow(at: Instant, zone: ZoneId): Window {
        val date = LocalDate.ofInstant(at, zone)
        return Window(
            start = date.atStartOfDay(zone).toInstant(),
            end = date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    /** The calendar month containing [first], in [zone]. */
    fun monthOf(first: LocalDate, zone: ZoneId): Window = Window(
        start = first.withDayOfMonth(1).atStartOfDay(zone).toInstant(),
        end = first.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant(),
    )

    /** Whole months back from the month containing [at], newest first. Used for the history list. */
    fun recentMonths(at: Instant, zone: ZoneId, count: Int): List<LocalDate> {
        val first = LocalDate.ofInstant(at, zone).withDayOfMonth(1)
        return (0 until count).map { first.minusMonths(it.toLong()) }
    }

    /**
     * A per-second rate restated over horizons a person actually thinks in.
     *
     * The ledger needs its own rather than borrowing the dividend engine's, and the difference
     * is not cosmetic. That one restates a month as the 400-year average (30.44 days), which is
     * right for a dividend cycle that has no relationship to the calendar. Here it would be
     * plainly wrong: a salary declared as RM3,000 a month would be reported back as RM2,945 a
     * month, and the person would be looking at a figure they had just typed in, changed.
     *
     * So a day is a day, a week is a week, and a month and a year are the real ones containing
     * [at]. Negative rates -- outgoings exceeding income -- pass through unchanged.
     */
    fun rateBreakdown(ratePerSecond: BigDecimal, at: Instant, zone: ZoneId): LedgerRateBreakdown {
        fun over(seconds: Long, scale: (BigDecimal) -> BigDecimal) =
            scale(ratePerSecond.multiply(BigDecimal.valueOf(seconds)))

        return LedgerRateBreakdown(
            perSecond = Money.rate(ratePerSecond),
            // Sub-daily horizons are fractions of a cent, so they keep 8dp -- rounding "per
            // minute" to cents would report RM0.00 for a real, growing figure.
            perMinute = over(60L, Money::accrual),
            perHour = over(3_600L, Money::accrual),
            // A day or more is a real amount of money, so it reads in currency precision.
            perDay = over(periodSeconds(CashFlowPeriod.DAILY, at, zone), Money::amount),
            perWeek = over(periodSeconds(CashFlowPeriod.WEEKLY, at, zone), Money::amount),
            perMonth = over(periodSeconds(CashFlowPeriod.MONTHLY, at, zone), Money::amount),
            perYear = over(periodSeconds(CashFlowPeriod.YEARLY, at, zone), Money::amount),
        )
    }

    /** Days from [at] to the end of its month, for the "resets in" line under the counter. */
    fun daysLeftInMonth(at: Instant, zone: ZoneId): Long {
        val date = LocalDate.ofInstant(at, zone)
        return ChronoUnit.DAYS.between(date, date.withDayOfMonth(1).plusMonths(1))
    }
}
