package com.dividendstream.api.marketdata

import com.dividendstream.api.dividend.DividendFrequency
import java.math.BigDecimal
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

        val projected = projectNext(sorted, frequency, currency, today, paymentLagDays)
        return if (projected == null) reported else reported + projected
    }

    /** History must span about a year before its seasonal shape means anything. */
    private const val MIN_HISTORY_DAYS = 300L

    /** Anniversaries landing this close together are the same slot in the payout year. */
    private const val SAME_SLOT_DAYS = 45L

    /**
     * A projection may not skip a payout year. Beyond this, the issuer has evidently stopped
     * paying (or Yahoo's history is stale), and the honest output is no forecast at all.
     */
    private const val MAX_PROJECTION_GAP_DAYS = 400L

    private data class Cycle(val exDate: LocalDate, val amount: BigDecimal)

    private fun projectNext(
        history: List<YahooFinanceClient.DividendEvent>,
        frequency: DividendFrequency,
        currency: String,
        today: LocalDate,
        paymentLagDays: Long,
    ): ProviderDividend? {
        val latest = history.last()

        // If the reported history already reaches into the future there is nothing to
        // project; inventing a further cycle on top would be pure fabrication.
        if (!latest.exDate.isBefore(today)) return null

        val next = seasonalCycle(history, today)
            ?: Cycle(latest.exDate.plusDays(frequency.accumulationDays), latest.amount)

        if (ChronoUnit.DAYS.between(latest.exDate, next.exDate) > MAX_PROJECTION_GAP_DAYS) return null

        val paymentDate = estimatePaymentDate(next.exDate, paymentLagDays)

        // A projection whose payment date has already passed is not a forecast, it is a
        // missing announcement. Better to show nothing than to date it in the past.
        if (!paymentDate.isAfter(today)) return null

        return ProviderDividend(
            dividendPerShare = next.amount,
            currency = currency,
            frequency = frequency,
            exDate = next.exDate,
            recordDate = null,
            paymentDate = paymentDate,
            sourceTag = SOURCE_PROJECTED,
        )
    }

    /**
     * Predicts the next cycle from the payout *calendar* rather than from an average gap.
     *
     * Dividend cycles are seasonal — they hang off the issuer's financial year, not off a
     * stopwatch — and the spacing between them is often deliberately uneven. BIMB is the
     * case in point: a final dividend goes ex in December and a small interim in March, so
     * the gaps alternate ~90 and ~275 days. Adding the 182-day median to the March cycle
     * lands in September, a month BIMB has never gone ex in.
     *
     * So each past ex-date is rolled forward whole years until it passes [today], and the
     * earliest such anniversary wins. Where several years share that slot the most recent
     * one is the anchor, because its amount is the current one — repeating the *latest*
     * dividend instead would price a December final at the March interim's rate.
     *
     * Returns null when there is too little history for a seasonal pattern to exist, leaving
     * the caller to fall back to fixed-interval spacing.
     */
    private fun seasonalCycle(
        history: List<YahooFinanceClient.DividendEvent>,
        today: LocalDate,
    ): Cycle? {
        if (ChronoUnit.DAYS.between(history.first().exDate, history.last().exDate) < MIN_HISTORY_DAYS) {
            return null
        }

        val anniversaries = history.map { it to nextAnniversary(it.exDate, today) }
        val earliest = anniversaries.minOf { (_, on) -> on }
        val (anchor, exDate) = anniversaries
            .filter { (_, on) -> ChronoUnit.DAYS.between(earliest, on) <= SAME_SLOT_DAYS }
            .maxBy { (event, _) -> event.exDate }

        return Cycle(exDate, anchor.amount)
    }

    private fun nextAnniversary(exDate: LocalDate, today: LocalDate): LocalDate {
        var candidate = exDate
        while (!candidate.isAfter(today)) candidate = candidate.plusYears(1)
        return candidate
    }
}
