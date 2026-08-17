package com.dividendstream.app.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** The parameters needed to compute one accruing dividend, as supplied by the backend. */
data class AccumulationStream(
    val expectedAmount: BigDecimal,
    val ratePerSecond: BigDecimal,
    val start: Instant,
    val end: Instant,
)

/**
 * Client-side mirror of the backend's `DividendAccumulationEngine`.
 *
 * The two implementations must agree exactly: the app ticks locally between refreshes, and
 * if the formulas diverged the number would visibly jump every time a response landed.
 * The rules that keep them in step are the ones with real consequences -- clamp at both
 * ends of the window, round DOWN to 8dp, and return the expected amount exactly (not the
 * rate multiplied out) once the payment date is reached.
 *
 * Because the result depends only on the timestamp, closing and reopening the app resumes
 * at the correct value rather than restarting from zero. Nothing is counted or stored
 * between readings.
 */
object AccumulationCalculator {

    const val DISPLAY_SCALE = 8

    private val ZERO: BigDecimal = BigDecimal.ZERO.setScale(DISPLAY_SCALE)

    fun accruedAt(stream: AccumulationStream, at: Instant): BigDecimal {
        if (stream.expectedAmount.signum() <= 0) return ZERO
        if (!at.isAfter(stream.start)) return ZERO
        if (!at.isBefore(stream.end)) return stream.expectedAmount.setScale(DISPLAY_SCALE, RoundingMode.DOWN)

        val elapsedSeconds = Duration.between(stream.start, at).seconds
        val raw = stream.ratePerSecond.multiply(BigDecimal.valueOf(elapsedSeconds))
        return raw.min(stream.expectedAmount).setScale(DISPLAY_SCALE, RoundingMode.DOWN)
    }

    fun totalAccruedAt(streams: List<AccumulationStream>, at: Instant): BigDecimal =
        streams.fold(ZERO) { sum, stream -> sum + accruedAt(stream, at) }

    /**
     * Combined rate across the streams that are genuinely mid-window at [at]. A cycle that
     * has not started, or has already matured, contributes nothing -- counting it would show
     * the money growing faster than it is.
     */
    fun combinedRatePerSecond(streams: List<AccumulationStream>, at: Instant): BigDecimal =
        streams
            .filter { at.isAfter(it.start) && at.isBefore(it.end) }
            .fold(BigDecimal.ZERO) { sum, stream -> sum + stream.ratePerSecond }

    /** Fraction of the window elapsed, in `[0, 1]`. Display only; carries no monetary meaning. */
    fun progressAt(stream: AccumulationStream, at: Instant): Float {
        val total = Duration.between(stream.start, stream.end).seconds
        if (total <= 0) return 1f
        val elapsed = Duration.between(stream.start, at).seconds.coerceIn(0L, total)
        return elapsed.toFloat() / total.toFloat()
    }
}
