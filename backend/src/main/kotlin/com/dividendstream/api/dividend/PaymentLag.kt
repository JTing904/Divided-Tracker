package com.dividendstream.api.dividend

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How long after the shares go ex an issuer actually pays.
 *
 * No free source publishes payment dates for Bursa Malaysia, so every one this application shows
 * is its own estimate: the ex-date plus a fixed default. That default is a guess about a company
 * nobody measured. Once somebody reports when the money genuinely arrived, the company has
 * measured itself, and the guess can be retired for that issuer.
 *
 * Pure, so the inference can be checked without a database.
 */
object PaymentLag {

    /** An observation: shares went ex on [exDate], money arrived on [paidOn]. */
    data class Observation(val exDate: LocalDate, val paidOn: LocalDate)

    /**
     * The lag to use for this issuer, or null when there is nothing to go on.
     *
     * The median rather than the mean, for the same reason the payout frequency uses one: a
     * single cycle delayed over a holiday would otherwise drag every future estimate with it.
     *
     * Nonsensical observations are dropped rather than trusted -- a payment dated before its own
     * ex-date is a typo, and a lag beyond a quarter is not a payment lag at all but a
     * misremembered year. Both would poison the median they were included in.
     */
    fun infer(observations: List<Observation>): Long? {
        val lags = observations
            .map { ChronoUnit.DAYS.between(it.exDate, it.paidOn) }
            .filter { it in MIN_PLAUSIBLE_DAYS..MAX_PLAUSIBLE_DAYS }
            .sorted()

        if (lags.isEmpty()) return null
        return lags[lags.size / 2]
    }

    /** A payment on the ex-date itself is not one; entitlement is settled after it. */
    private const val MIN_PLAUSIBLE_DAYS = 1L

    /** Bursa issuers pay within weeks. Anything past a quarter is a mistyped year, not a lag. */
    private const val MAX_PLAUSIBLE_DAYS = 120L
}
