package com.dividendstream.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * These assertions intentionally duplicate the backend's engine tests. The client computes
 * the counter locally between refreshes, so any divergence would show up as the number
 * jumping whenever a response arrived.
 */
class AccumulationCalculatorTest {

    private val start = Instant.parse("2026-03-01T00:00:00Z")
    private val end = start.plus(Duration.ofDays(1))

    // RM864.00 across one day is exactly RM0.01 per second.
    private val stream = AccumulationStream(
        expectedAmount = BigDecimal("864.00"),
        ratePerSecond = BigDecimal("0.010000000000"),
        start = start,
        end = end,
    )

    @Test
    fun `is zero before the window opens`() {
        assertEquals(0, AccumulationCalculator.accruedAt(stream, start.minusSeconds(60)).compareTo(BigDecimal.ZERO))
        assertEquals(0, AccumulationCalculator.accruedAt(stream, start).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `is elapsed seconds times the rate part way through`() {
        val sixHoursIn = AccumulationCalculator.accruedAt(stream, start.plus(Duration.ofHours(6)))
        assertEquals(0, sixHoursIn.compareTo(BigDecimal("216.00")))
    }

    @Test
    fun `lands exactly on the expected amount at the payment date`() {
        assertEquals(0, AccumulationCalculator.accruedAt(stream, end).compareTo(BigDecimal("864.00")))
    }

    @Test
    fun `never exceeds the expected amount`() {
        val wayPast = AccumulationCalculator.accruedAt(stream, end.plus(Duration.ofDays(400)))
        assertEquals(0, wayPast.compareTo(BigDecimal("864.00")))

        // Even if the stored rate were far too large for the window.
        val corrupted = stream.copy(ratePerSecond = BigDecimal("999.0"))
        val clamped = AccumulationCalculator.accruedAt(corrupted, start.plus(Duration.ofHours(12)))
        assertEquals(0, clamped.compareTo(BigDecimal("864.00")))
    }

    @Test
    fun `never goes backwards as time advances`() {
        var previous = BigDecimal.ZERO
        repeat(48) { halfHour ->
            val at = start.plus(Duration.ofMinutes(30L * halfHour))
            val current = AccumulationCalculator.accruedAt(stream, at)
            assertTrue("accrual decreased at $at", current >= previous)
            previous = current
        }
    }

    @Test
    fun `resumes at the same value for the same instant`() {
        // Reopening the app is exactly this: recomputing from the timestamp.
        val moment = start.plus(Duration.ofHours(9)).plusSeconds(17)
        val before = AccumulationCalculator.accruedAt(stream, moment)
        val after = AccumulationCalculator.accruedAt(stream, moment)

        assertEquals(before, after)
        assertTrue(AccumulationCalculator.accruedAt(stream, moment.plusSeconds(30)) > before)
    }

    @Test
    fun `totals a portfolio and sums only the rates that are running`() {
        val notStarted = stream.copy(start = end, end = end.plus(Duration.ofDays(1)))
        val finished = stream.copy(start = start.minus(Duration.ofDays(2)), end = start.minusSeconds(1))
        val streams = listOf(stream, notStarted, finished)

        val midway = start.plus(Duration.ofHours(12))

        // 432.00 accrued + 0.00 not started + 864.00 complete
        assertEquals(
            0,
            AccumulationCalculator.totalAccruedAt(streams, midway).compareTo(BigDecimal("1296.00")),
        )

        // Only the one genuinely mid-window contributes to the live rate.
        assertEquals(
            0,
            AccumulationCalculator.combinedRatePerSecond(streams, midway).compareTo(BigDecimal("0.01")),
        )
    }

    @Test
    fun `reports progress through the window`() {
        assertEquals(0f, AccumulationCalculator.progressAt(stream, start), 0.001f)
        assertEquals(0.5f, AccumulationCalculator.progressAt(stream, start.plus(Duration.ofHours(12))), 0.001f)
        assertEquals(1f, AccumulationCalculator.progressAt(stream, end.plus(Duration.ofDays(5))), 0.001f)
    }
}
