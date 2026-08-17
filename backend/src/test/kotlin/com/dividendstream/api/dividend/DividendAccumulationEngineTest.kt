package com.dividendstream.api.dividend

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * The engine decides what number the user watches grow, so it is tested directly rather
 * than through the API.
 */
class DividendAccumulationEngineTest {

    private val start: Instant = Instant.parse("2026-03-01T00:00:00Z")
    private val oneDayWindowEnd: Instant = start.plus(Duration.ofDays(1))

    @Nested
    @DisplayName("expected amount")
    inner class ExpectedAmount {

        @Test
        fun `multiplies shares by dividend per share`() {
            val expected = DividendAccumulationEngine.expectedAmount(
                shares = BigDecimal("1000"),
                dividendPerShare = BigDecimal("0.32"),
            )

            // Exactly RM320.00 -- not 319.99999999997.
            assertThat(expected).isEqualByComparingTo(BigDecimal("320.00"))
            assertThat(expected.scale()).isEqualTo(2)
        }

        @Test
        fun `rounds half up to currency precision`() {
            // 333 x 0.005 = 1.665, which must settle to 1.67 rather than 1.66.
            val expected = DividendAccumulationEngine.expectedAmount(
                shares = BigDecimal("333"),
                dividendPerShare = BigDecimal("0.005"),
            )

            assertThat(expected).isEqualByComparingTo(BigDecimal("1.67"))
        }

        @Test
        fun `handles fractional share counts`() {
            val expected = DividendAccumulationEngine.expectedAmount(
                shares = BigDecimal("150.5000"),
                dividendPerShare = BigDecimal("0.12000000"),
            )

            assertThat(expected).isEqualByComparingTo(BigDecimal("18.06"))
        }

        @Test
        fun `rejects negative inputs`() {
            assertThatThrownBy {
                DividendAccumulationEngine.expectedAmount(BigDecimal("-1"), BigDecimal("0.32"))
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                DividendAccumulationEngine.expectedAmount(BigDecimal("100"), BigDecimal("-0.01"))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("per-second rate")
    inner class RatePerSecond {

        @Test
        fun `divides the expected amount evenly across the window`() {
            // RM864.00 across exactly one day is a clean RM0.01 per second.
            val rate = DividendAccumulationEngine.ratePerSecond(
                expectedAmount = BigDecimal("864.00"),
                start = start,
                end = oneDayWindowEnd,
            )

            assertThat(rate).isEqualByComparingTo(BigDecimal("0.01"))
            assertThat(rate.scale()).isEqualTo(12)
        }

        @Test
        fun `keeps enough precision that a semi-annual dividend is not rounded away`() {
            val end = start.plus(Duration.ofDays(182))
            val rate = DividendAccumulationEngine.ratePerSecond(BigDecimal("320.00"), start, end)

            // Rounding this to cents would give 0.00, and the counter would never move.
            assertThat(rate.signum()).isEqualTo(1)

            // Multiplying back out recovers the expected amount to within one cent.
            val recovered = rate.multiply(BigDecimal(182L * 86_400L))
            assertThat(recovered.subtract(BigDecimal("320.00")).abs())
                .isLessThan(BigDecimal("0.01"))
        }

        @Test
        fun `rejects a window that does not move forward`() {
            assertThatThrownBy {
                DividendAccumulationEngine.ratePerSecond(BigDecimal("320.00"), start, start)
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                DividendAccumulationEngine.ratePerSecond(BigDecimal("320.00"), oneDayWindowEnd, start)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("accrued value")
    inner class Accrual {

        private val expected = BigDecimal("864.00")
        private val rate = DividendAccumulationEngine.ratePerSecond(expected, start, oneDayWindowEnd)

        private fun accruedAt(at: Instant) = DividendAccumulationEngine.accruedAt(
            expectedAmount = expected,
            ratePerSecond = rate,
            accumulationStart = start,
            accumulationEnd = oneDayWindowEnd,
            at = at,
        )

        @Test
        fun `is zero before and at the start of the window`() {
            assertThat(accruedAt(start.minusSeconds(3_600))).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(accruedAt(start)).isEqualByComparingTo(BigDecimal.ZERO)
        }

        @Test
        fun `is elapsed seconds times the rate part way through`() {
            // Six hours in: 21,600 seconds x RM0.01.
            assertThat(accruedAt(start.plus(Duration.ofHours(6))))
                .isEqualByComparingTo(BigDecimal("216.00"))

            // Halfway is exactly half the dividend.
            assertThat(accruedAt(start.plus(Duration.ofHours(12))))
                .isEqualByComparingTo(BigDecimal("432.00"))
        }

        @Test
        fun `equals the expected amount exactly at the payment date`() {
            assertThat(accruedAt(oneDayWindowEnd)).isEqualByComparingTo(expected)
        }

        @Test
        fun `never exceeds the expected amount after the payment date`() {
            assertThat(accruedAt(oneDayWindowEnd.plus(Duration.ofDays(365))))
                .isEqualByComparingTo(expected)
        }

        @Test
        fun `is clamped even when the stored rate is too large for the window`() {
            // A corrupted or stale rate must never produce more money than is expected.
            val inflated = DividendAccumulationEngine.accruedAt(
                expectedAmount = expected,
                ratePerSecond = BigDecimal("999.999999999999"),
                accumulationStart = start,
                accumulationEnd = oneDayWindowEnd,
                at = start.plus(Duration.ofHours(12)),
            )

            assertThat(inflated).isEqualByComparingTo(expected)
        }

        @Test
        fun `never decreases as time moves forward`() {
            var previous = BigDecimal.ZERO
            repeat(24) { hour ->
                val current = accruedAt(start.plus(Duration.ofHours(hour.toLong())))
                assertThat(current).isGreaterThanOrEqualTo(previous)
                previous = current
            }
        }

        @Test
        fun `returns the same value for the same instant, so restarting the app resumes`() {
            // The whole reason nothing is persisted per tick: the value is a pure function
            // of the timestamp, so closing and reopening the app cannot reset it.
            val moment = start.plus(Duration.ofHours(7)).plusSeconds(43)

            val beforeRestart = accruedAt(moment)
            val afterRestart = accruedAt(moment)

            assertThat(afterRestart).isEqualByComparingTo(beforeRestart)
            assertThat(accruedAt(moment.plusSeconds(60))).isGreaterThan(beforeRestart)
        }

        @Test
        fun `is zero when nothing is expected`() {
            val nothing = DividendAccumulationEngine.accruedAt(
                expectedAmount = BigDecimal.ZERO,
                ratePerSecond = BigDecimal.ZERO,
                accumulationStart = start,
                accumulationEnd = oneDayWindowEnd,
                at = start.plus(Duration.ofHours(12)),
            )

            assertThat(nothing).isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Nested
    @DisplayName("status")
    inner class Status {

        @Test
        fun `follows the clock through the window`() {
            fun statusAt(at: Instant) = DividendAccumulationEngine.statusAt(
                DividendStatus.UPCOMING, start, oneDayWindowEnd, at,
            )

            assertThat(statusAt(start.minusSeconds(1))).isEqualTo(DividendStatus.UPCOMING)
            assertThat(statusAt(start)).isEqualTo(DividendStatus.ACCUMULATING)
            assertThat(statusAt(start.plus(Duration.ofHours(12)))).isEqualTo(DividendStatus.ACCUMULATING)
            assertThat(statusAt(oneDayWindowEnd)).isEqualTo(DividendStatus.PAYABLE)
        }

        @Test
        fun `leaves settled and cancelled entitlements alone`() {
            val long_after = oneDayWindowEnd.plus(Duration.ofDays(30))

            assertThat(DividendAccumulationEngine.statusAt(DividendStatus.PAID, start, oneDayWindowEnd, long_after))
                .isEqualTo(DividendStatus.PAID)
            assertThat(DividendAccumulationEngine.statusAt(DividendStatus.CANCELLED, start, oneDayWindowEnd, long_after))
                .isEqualTo(DividendStatus.CANCELLED)
        }
    }

    @Nested
    @DisplayName("rate breakdown")
    inner class Breakdown {

        @Test
        fun `restates a per-second rate over longer horizons`() {
            val breakdown = DividendAccumulationEngine.rateBreakdown(BigDecimal("0.010000000000"))

            assertThat(breakdown.perMinute).isEqualByComparingTo(BigDecimal("0.60"))
            assertThat(breakdown.perHour).isEqualByComparingTo(BigDecimal("36.00"))
            assertThat(breakdown.perDay).isEqualByComparingTo(BigDecimal("864.00"))
        }

        @Test
        fun `keeps a realistic per-minute rate visible instead of rounding it to zero`() {
            // A RM320 semi-annual dividend accrues about RM0.0000204 per second. Rounded to
            // cents, every horizon below a day would read RM0.00.
            val breakdown = DividendAccumulationEngine.rateBreakdown(BigDecimal("0.000020350020"))

            assertThat(breakdown.perSecond.signum()).isEqualTo(1)
            assertThat(breakdown.perMinute.signum()).isEqualTo(1)
            assertThat(breakdown.perHour.signum()).isEqualTo(1)
            assertThat(breakdown.perDay).isEqualByComparingTo(BigDecimal("1.76"))
        }
    }

    @Nested
    @DisplayName("the worked example from the product brief")
    inner class WorkedExample {

        @Test
        fun `1000 shares at RM0_32 semi-annually accrues at roughly RM0_00002 per second`() {
            val end = start.plus(Duration.ofDays(DividendFrequency.SEMI_ANNUAL.accumulationDays))
            val expected = DividendAccumulationEngine.expectedAmount(BigDecimal("1000"), BigDecimal("0.32"))
            val rate = DividendAccumulationEngine.ratePerSecond(expected, start, end)

            assertThat(expected).isEqualByComparingTo(BigDecimal("320.00"))
            assertThat(rate).isBetween(BigDecimal("0.00002"), BigDecimal("0.000021"))

            // Roughly 40% of the way through, the counter sits near RM128.
            val partWay = start.plus(Duration.ofDays(73))
            val accrued = DividendAccumulationEngine.accruedAt(expected, rate, start, end, partWay)
            assertThat(accrued).isBetween(BigDecimal("127.00"), BigDecimal("129.00"))

            // And it lands exactly on the expected amount at payment.
            assertThat(DividendAccumulationEngine.accruedAt(expected, rate, start, end, end))
                .isEqualByComparingTo(BigDecimal("320.00"))
        }

        @Test
        fun `a three-stock portfolio accumulates at the sum of its rates`() {
            val end = start.plus(Duration.ofDays(DividendFrequency.SEMI_ANNUAL.accumulationDays))

            val positions = listOf(
                BigDecimal("1000") to BigDecimal("0.32"), // Maybank  -> RM320
                BigDecimal("500") to BigDecimal("0.36"), // CIMB     -> RM180
                BigDecimal("300") to BigDecimal("0.25"), // Tenaga   -> RM75
            )

            val expectedTotal = positions
                .map { (shares, dps) -> DividendAccumulationEngine.expectedAmount(shares, dps) }
                .reduce(BigDecimal::add)
            assertThat(expectedTotal).isEqualByComparingTo(BigDecimal("575.00"))

            val combinedRate = positions
                .map { (shares, dps) ->
                    DividendAccumulationEngine.ratePerSecond(
                        DividendAccumulationEngine.expectedAmount(shares, dps), start, end,
                    )
                }
                .reduce(BigDecimal::add)

            // Summing the individual rates matches spreading the total across the window.
            val rateOnTotal = DividendAccumulationEngine.ratePerSecond(expectedTotal, start, end)
            assertThat(combinedRate.subtract(rateOnTotal).abs())
                .isLessThan(BigDecimal("0.000000001"))
        }
    }
}
