package com.dividendstream.api.dividend

import com.dividendstream.api.common.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * The dividend accumulation maths. Pure by design.
 *
 * Nothing here reads a clock, a database or a request: every result is a function of its
 * arguments alone. That is what makes the same formula safe to run on the backend and,
 * independently, on the Android client between refreshes -- both arrive at the same number
 * for the same instant, so the counter never jumps when a refresh lands.
 *
 * It is also why no row is ever written per second. The database stores the *parameters*
 * (expected amount, window, rate); the value is derived on demand.
 */
object DividendAccumulationEngine {

    /** Average seconds per month over a 400-year Gregorian cycle. */
    const val SECONDS_PER_MONTH = 2_629_746L

    /** Average seconds per year over a 400-year Gregorian cycle (365.2425 days). */
    const val SECONDS_PER_YEAR = 31_556_952L

    /**
     * The dividend a holding is expected to produce: `shares x dividendPerShare`,
     * rounded to currency precision.
     */
    fun expectedAmount(shares: BigDecimal, dividendPerShare: BigDecimal): BigDecimal {
        require(shares.signum() >= 0) { "shares must not be negative" }
        require(dividendPerShare.signum() >= 0) { "dividendPerShare must not be negative" }
        return Money.amount(shares.multiply(dividendPerShare))
    }

    /** Length of the accumulation window. Must be positive to be divisible. */
    fun accumulationSeconds(start: Instant, end: Instant): Long {
        val seconds = Duration.between(start, end).seconds
        require(seconds > 0) { "accumulation window must end after it starts" }
        return seconds
    }

    /**
     * Spreads [expectedAmount] evenly across the window.
     *
     * Held at [Money.RATE_SCALE] (12dp) because the figure is tiny -- RM320 over 30 days is
     * about RM0.000123456790 per second -- and gets multiplied by a large elapsed-second
     * count before anyone sees it. Rounding it to cents here would make the counter wrong
     * by whole ringgit within a day.
     */
    fun ratePerSecond(expectedAmount: BigDecimal, start: Instant, end: Instant): BigDecimal {
        require(expectedAmount.signum() >= 0) { "expectedAmount must not be negative" }
        return expectedAmount.divide(
            BigDecimal.valueOf(accumulationSeconds(start, end)),
            Money.RATE_SCALE,
            RoundingMode.HALF_UP,
        )
    }

    /**
     * Value accrued by [at].
     *
     * Clamped to `[0, expectedAmount]`: before the ex-date nothing has accrued, and the
     * estimate can never overshoot the dividend actually expected. At or after
     * [accumulationEnd] the result is exactly [expectedAmount] -- not the rate multiplied
     * out, which would land a fraction short because the rate itself is rounded.
     */
    fun accruedAt(
        expectedAmount: BigDecimal,
        ratePerSecond: BigDecimal,
        accumulationStart: Instant,
        accumulationEnd: Instant,
        at: Instant,
    ): BigDecimal {
        if (expectedAmount.signum() <= 0) return Money.ZERO_ACCRUAL
        if (!at.isAfter(accumulationStart)) return Money.ZERO_ACCRUAL
        if (!at.isBefore(accumulationEnd)) return Money.accrual(expectedAmount)

        val elapsedSeconds = Duration.between(accumulationStart, at).seconds
        val raw = ratePerSecond.multiply(BigDecimal.valueOf(elapsedSeconds))
        return Money.accrual(raw.min(expectedAmount))
    }

    /**
     * How far through the window [at] falls, as a fraction in `[0, 1]`. Useful for progress
     * indicators; carries no monetary meaning.
     */
    fun progressAt(accumulationStart: Instant, accumulationEnd: Instant, at: Instant): BigDecimal {
        val total = accumulationSeconds(accumulationStart, accumulationEnd)
        val elapsed = Duration.between(accumulationStart, at).seconds.coerceIn(0L, total)
        return BigDecimal.valueOf(elapsed)
            .divide(BigDecimal.valueOf(total), 6, RoundingMode.DOWN)
    }

    /**
     * The status implied by the clock. Settled and cancelled entitlements are terminal and
     * are returned untouched -- only a settlement job may move a row to PAID.
     */
    fun statusAt(
        current: DividendStatus,
        accumulationStart: Instant,
        accumulationEnd: Instant,
        at: Instant,
    ): DividendStatus = when {
        current == DividendStatus.PAID || current == DividendStatus.CANCELLED -> current
        at.isBefore(accumulationStart) -> DividendStatus.UPCOMING
        at.isBefore(accumulationEnd) -> DividendStatus.ACCUMULATING
        else -> DividendStatus.PAYABLE
    }

    /**
     * Restates a per-second rate over longer horizons for the dashboard.
     *
     * These describe the *current* accumulation pace, not a forecast of future dividends.
     */
    fun rateBreakdown(ratePerSecond: BigDecimal): RateBreakdown = RateBreakdown(
        // Full precision: this is the one figure a client may compute with rather than
        // just display, so it must survive the round trip intact.
        perSecond = Money.rate(ratePerSecond),
        // Sub-daily horizons are fractions of a cent and keep 8dp -- rounding "per minute"
        // to currency precision would report RM0.00 for a real, growing rate.
        perMinute = scaleRate(ratePerSecond, 60L, Money::accrual),
        perHour = scaleRate(ratePerSecond, 3_600L, Money::accrual),
        // A day or more is a real amount of money, so it reads in currency precision.
        perDay = scaleRate(ratePerSecond, 86_400L, Money::amount),
        perMonth = scaleRate(ratePerSecond, SECONDS_PER_MONTH, Money::amount),
        perYear = scaleRate(ratePerSecond, SECONDS_PER_YEAR, Money::amount),
    )

    private fun scaleRate(
        ratePerSecond: BigDecimal,
        seconds: Long,
        round: (BigDecimal) -> BigDecimal,
    ): BigDecimal = round(ratePerSecond.multiply(BigDecimal.valueOf(seconds)))
}

/** A per-second rate restated over human-readable horizons. */
data class RateBreakdown(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)
