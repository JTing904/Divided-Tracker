package com.dividendstream.api.marketdata

import com.dividendstream.api.dividend.DividendFrequency
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Turns Yahoo's dividend history into the cycles this application needs.
 *
 * Yahoo reports two facts per dividend: the **ex-date** and the **amount**. It does not
 * report the payment date, the record date, or anything that has been declared but has not
 * yet gone ex. This object is where that gap is bridged, and every value it invents is
 * tagged so the rest of the system — and the user — can tell it apart from a reported fact.
 *
 * Pure and clock-free: callers pass `today`, so the behaviour is fully testable.
 */
object YahooDividendCalendar {

    /** Source tags written to `dividends.source`, and read back to label the UI. */
    const val SOURCE_REPORTED = "yahoo"
    const val SOURCE_PROJECTED = "yahoo-projected"

    /**
     * Infers how often the stock pays from the spacing of its ex-dates.
     *
     * The median gap is used rather than the mean because a single special dividend would
     * otherwise drag a semi-annual payer towards "quarterly" and double its apparent rate.
     * With fewer than two observations nothing can be inferred, so the longest period is
     * assumed — that understates the per-second rate rather than overstating it, which is
     * the right way to be wrong about money the user has not received.
     */
    fun inferFrequency(exDates: List<LocalDate>): DividendFrequency {
        val sorted = exDates.distinct().sorted()
        if (sorted.size < 2) return DividendFrequency.ANNUAL

        val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
        val median = gaps[gaps.size / 2]

        return when {
            median < 45 -> DividendFrequency.MONTHLY
            median < 135 -> DividendFrequency.QUARTERLY
            median < 250 -> DividendFrequency.SEMI_ANNUAL
            else -> DividendFrequency.ANNUAL
        }
    }

    /**
     * Estimates when a dividend is actually paid.
     *
     * Bursa Malaysia issuers typically pay a few weeks after the shares go ex. The exact
     * date comes from the company's announcement, which Yahoo does not carry, so this is an
     * estimate and is recorded as one.
     */
    fun estimatePaymentDate(exDate: LocalDate, paymentLagDays: Long): LocalDate =
        exDate.plusDays(paymentLagDays.coerceAtLeast(1))

    /**
     * Builds the cycles to store: every reported dividend, plus at most one projected
     * upcoming cycle.
     *
     * The projection exists because the product is a live counter — with no future cycle
     * there is nothing to accumulate towards and the dashboard reads zero. It is only added
     * when the reported history has run out, it repeats the most recent amount at the
     * inferred cadence, and it is tagged [SOURCE_PROJECTED] so it can be labelled as a
     * projection rather than an announcement.
     */
    fun toCycles(
        events: List<YahooFinanceClient.DividendEvent>,
        currency: String,
        today: LocalDate,
        paymentLagDays: Long,
    ): List<ProviderDividend> {
        if (events.isEmpty()) return emptyList()

        val sorted = events.sortedBy { it.exDate }
        val frequency = inferFrequency(sorted.map { it.exDate })

        val reported = sorted.map { event ->
            ProviderDividend(
                dividendPerShare = event.amount,
                currency = currency,
                frequency = frequency,
                exDate = event.exDate,
                // Yahoo does not report it, and guessing a specific day would be worse than
                // admitting it is unknown.
                recordDate = null,
                paymentDate = estimatePaymentDate(event.exDate, paymentLagDays),
                sourceTag = SOURCE_REPORTED,
            )
        }

        val projected = projectNext(sorted.last(), frequency, currency, today, paymentLagDays)
        return if (projected == null) reported else reported + projected
    }

    private fun projectNext(
        latest: YahooFinanceClient.DividendEvent,
        frequency: DividendFrequency,
        currency: String,
        today: LocalDate,
        paymentLagDays: Long,
    ): ProviderDividend? {
        val nextExDate = latest.exDate.plusDays(frequency.accumulationDays)
        val paymentDate = estimatePaymentDate(nextExDate, paymentLagDays)

        // If the reported history already reaches into the future there is nothing to
        // project; inventing a further cycle on top would be pure fabrication.
        if (!latest.exDate.isBefore(today)) return null

        // A projection whose payment date has already passed is not a forecast, it is a
        // missing announcement. Better to show nothing than to date it in the past.
        if (!paymentDate.isAfter(today)) return null

        return ProviderDividend(
            dividendPerShare = latest.amount,
            currency = currency,
            frequency = frequency,
            exDate = nextExDate,
            recordDate = null,
            paymentDate = paymentDate,
            sourceTag = SOURCE_PROJECTED,
        )
    }
}
