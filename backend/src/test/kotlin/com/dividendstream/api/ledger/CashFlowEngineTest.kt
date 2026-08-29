package com.dividendstream.api.ledger

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The engine decides what number the user watches move, so it is tested directly rather than
 * through the API.
 */
class CashFlowEngineTest {

    private val kl: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    /** Midday on 15 August 2026 in Kuala Lumpur. */
    private val midAugust: Instant = Instant.parse("2026-08-15T04:00:00Z")

    /** The whole of one flow's contribution to the month containing [at]. */
    private fun project(
        amount: String,
        period: CashFlowPeriod,
        at: Instant = midAugust,
        startsOn: LocalDate = LocalDate.of(2020, 1, 1),
        endsOn: LocalDate? = null,
    ) = CashFlowEngine.project(
        amount = BigDecimal(amount),
        period = period,
        startsOn = startsOn,
        endsOn = endsOn,
        month = CashFlowEngine.monthWindow(at, kl),
        at = at,
        zone = kl,
    )

    @Nested
    @DisplayName("the month window")
    inner class MonthWindow {

        @Test
        fun `runs from local midnight to local midnight`() {
            val window = CashFlowEngine.monthWindow(midAugust, kl)

            // Kuala Lumpur is UTC+8, so the month begins eight hours before it does in UTC.
            assertThat(window.start).isEqualTo(Instant.parse("2026-07-31T16:00:00Z"))
            assertThat(window.end).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"))
        }

        @Test
        @DisplayName("an instant that is already next month locally belongs to next month")
        fun `zone decides which month an instant falls in`() {
            // 1 August 04:00 UTC is noon on the 1st in KL -- August.
            assertThat(CashFlowEngine.monthWindow(Instant.parse("2026-08-01T04:00:00Z"), kl).start)
                .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"))

            // 31 July 20:00 UTC is already 1 August in KL, though it is still July in UTC.
            assertThat(CashFlowEngine.monthWindow(Instant.parse("2026-07-31T20:00:00Z"), kl).start)
                .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"))

            // 31 July 12:00 UTC is still 31 July in KL -- July.
            assertThat(CashFlowEngine.monthWindow(Instant.parse("2026-07-31T12:00:00Z"), kl).start)
                .isEqualTo(Instant.parse("2026-06-30T16:00:00Z"))
        }
    }

    @Nested
    @DisplayName("period length")
    inner class PeriodLength {

        @Test
        fun `a day is a day`() {
            assertThat(CashFlowEngine.periodSeconds(CashFlowPeriod.DAILY, midAugust, kl))
                .isEqualTo(86_400L)
        }

        @Test
        fun `a week is seven days`() {
            assertThat(CashFlowEngine.periodSeconds(CashFlowPeriod.WEEKLY, midAugust, kl))
                .isEqualTo(604_800L)
        }

        @Test
        @DisplayName("a month is the real month, not an average one")
        fun `month length follows the calendar`() {
            val august = CashFlowEngine.periodSeconds(CashFlowPeriod.MONTHLY, midAugust, kl)
            val february = CashFlowEngine.periodSeconds(
                CashFlowPeriod.MONTHLY, Instant.parse("2026-02-15T04:00:00Z"), kl,
            )

            assertThat(august).isEqualTo(31L * 86_400)
            assertThat(february).isEqualTo(28L * 86_400)
        }

        @Test
        fun `a leap year is a day longer`() {
            val leap = CashFlowEngine.periodSeconds(
                CashFlowPeriod.YEARLY, Instant.parse("2028-06-15T04:00:00Z"), kl,
            )
            val ordinary = CashFlowEngine.periodSeconds(CashFlowPeriod.YEARLY, midAugust, kl)

            assertThat(leap).isEqualTo(366L * 86_400)
            assertThat(ordinary).isEqualTo(365L * 86_400)
        }
    }

    @Nested
    @DisplayName("the rate")
    inner class Rate {

        @Test
        @DisplayName("a monthly salary lands on exactly the declared amount at month end")
        fun `monthly rate completes the month`() {
            // Not 2,999.9999998 and not 3,000.0000011 -- the figure the person typed in.
            assertThat(project("3000.00", CashFlowPeriod.MONTHLY).expected)
                .isEqualByComparingTo(BigDecimal("3000"))
        }

        @Test
        @DisplayName("the same salary in February also lands on the declared amount")
        fun `a short month does not shortchange the counter`() {
            val february = Instant.parse("2026-02-15T04:00:00Z")

            assertThat(project("3000.00", CashFlowPeriod.MONTHLY, at = february).expected)
                .isEqualByComparingTo(BigDecimal("3000"))
        }

        @Test
        @DisplayName("a daily allowance accrues once per day, so a 31-day month is 31 of them")
        fun `daily rate scales across the month`() {
            // A student on RM20 a day: RM620 across August, not RM20.
            assertThat(project("20.00", CashFlowPeriod.DAILY).expected)
                .isEqualByComparingTo(BigDecimal("620"))
        }

        @Test
        fun `a weekly allowance accrues seven days at a time`() {
            // RM140 a week is RM20 a day, so August is again RM620.
            assertThat(project("140.00", CashFlowPeriod.WEEKLY).expected)
                .isEqualByComparingTo(BigDecimal("620"))
        }

        @Test
        @DisplayName("a yearly premium contributes its share of the month, not the whole of it")
        fun `yearly rate spreads across the year`() {
            // RM1,200 a year, 31 of 2026's 365 days: 1200 x 31/365.
            assertThat(project("1200.00", CashFlowPeriod.YEARLY).expected)
                .isEqualByComparingTo(BigDecimal("101.91780821"))
        }

        @Test
        fun `the rate keeps twelve decimals`() {
            val rate = CashFlowEngine.ratePerSecond(BigDecimal("3000.00"), CashFlowPeriod.MONTHLY, midAugust, kl)

            assertThat(rate.scale()).isEqualTo(12)
            // 3000 / 2,678,400 seconds.
            assertThat(rate).isEqualByComparingTo(BigDecimal("0.001120071685"))
        }
    }

    @Nested
    @DisplayName("accrual")
    inner class Accrual {

        private val month = CashFlowEngine.monthWindow(midAugust, kl)

        private fun accruedAt(at: Instant): BigDecimal {
            val projected = project("3000.00", CashFlowPeriod.MONTHLY)
            return CashFlowEngine.accruedAt(projected.ratePerSecond, projected.expected, month, at)
        }

        @Test
        fun `nothing has accrued at the start of the window`() {
            assertThat(accruedAt(month.start)).isEqualByComparingTo(BigDecimal.ZERO)
        }

        @Test
        @DisplayName("halfway through the month, half the salary -- to a millionth of a cent")
        fun `accrues proportionally`() {
            val halfway = accruedAt(month.start.plusSeconds(month.seconds / 2))

            // 1500.00000055, not 1500 exactly, and that is the intended behaviour rather than
            // a defect being tolerated. Inside the window the figure is the rate multiplied
            // out, and the rate is rounded to 12dp; across 1.3 million seconds that rounding
            // surfaces in the seventh decimal place.
            //
            // Computing the interior from the declared amount instead would make it exact --
            // and would be wrong. The client ticks between refreshes from the rate, exactly as
            // it does for dividends, so the server has to arrive at the number the client
            // does, or the counter would visibly jump every time a response landed. The two
            // ends of the window, which are the figures that carry meaning, are exact.
            assertThat(halfway.subtract(BigDecimal("1500")).abs()).isLessThan(BigDecimal("0.000001"))
            assertThat(halfway).isEqualByComparingTo(BigDecimal("1500.00000055"))
        }

        @Test
        @DisplayName("past the end it stays at the declared amount rather than running on")
        fun `clamps at the end of the window`() {
            assertThat(accruedAt(month.end)).isEqualByComparingTo(BigDecimal("3000"))
            assertThat(accruedAt(month.end.plusSeconds(86_400))).isEqualByComparingTo(BigDecimal("3000"))
        }

        @Test
        @DisplayName("the running figure never creeps past the declared amount")
        fun `never exceeds the total`() {
            val projected = project("3000.00", CashFlowPeriod.MONTHLY)

            // The final seconds are where the rate multiplied out would otherwise overshoot.
            (0L..10L).forEach { back ->
                val at = month.end.minusSeconds(back)
                assertThat(CashFlowEngine.accruedAt(projected.ratePerSecond, projected.expected, month, at))
                    .isLessThanOrEqualTo(BigDecimal("3000"))
            }
        }
    }

    @Nested
    @DisplayName("start and end dates")
    inner class ActiveWindow {

        private val august = CashFlowEngine.monthWindow(midAugust, kl)

        @Test
        @DisplayName("a job begun mid-month earns from that day, not from the 1st")
        fun `start date clips the window`() {
            val projected = project("3000.00", CashFlowPeriod.MONTHLY, startsOn = LocalDate.of(2026, 8, 16))
            val window = projected.window!!

            assertThat(window.start).isEqualTo(Instant.parse("2026-08-15T16:00:00Z"))
            assertThat(window.end).isEqualTo(august.end)
            // 16 of August's 31 days: 3000 x 16/31.
            assertThat(projected.expected).isEqualByComparingTo(BigDecimal("1548.38709677"))
        }

        @Test
        @DisplayName("the end date is inclusive -- the last day still counts in full")
        fun `end date clips the window`() {
            val window = CashFlowEngine
                .activeWindow(august, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 10), kl)!!

            assertThat(window.start).isEqualTo(august.start)
            assertThat(window.end).isEqualTo(Instant.parse("2026-08-10T16:00:00Z"))
        }

        @Test
        fun `a flow that starts after this month is not live in it`() {
            assertThat(CashFlowEngine.activeWindow(august, LocalDate.of(2026, 9, 1), null, kl)).isNull()
        }

        @Test
        fun `a flow that ended before this month is not live in it`() {
            assertThat(
                CashFlowEngine.activeWindow(august, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31), kl),
            ).isNull()
        }

        @Test
        @DisplayName("a flow not live this month contributes nothing but still reports its rate")
        fun `an inactive flow contributes nothing`() {
            val projected = project("3000.00", CashFlowPeriod.MONTHLY, startsOn = LocalDate.of(2026, 9, 1))

            assertThat(projected.window).isNull()
            assertThat(projected.expected).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(projected.accrued).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(projected.ratePerSecond).isGreaterThan(BigDecimal.ZERO)
        }

        @Test
        fun `a flow spanning the whole month is the whole month`() {
            val window = CashFlowEngine.activeWindow(august, LocalDate.of(2020, 1, 1), null, kl)!!

            assertThat(window.start).isEqualTo(august.start)
            assertThat(window.end).isEqualTo(august.end)
        }
    }

    @Nested
    @DisplayName("expected over a window")
    inner class ExpectedOver {

        @Test
        @DisplayName("comes from the declared amount, not the rounded rate multiplied back out")
        fun `no drift from the rate`() {
            val month = CashFlowEngine.monthWindow(midAugust, kl)
            val amount = BigDecimal("3000.00")
            val rate = CashFlowEngine.ratePerSecond(amount, CashFlowPeriod.MONTHLY, midAugust, kl)

            val fromAmount = CashFlowEngine.expectedOver(amount, month.seconds, month)
            val fromRate = rate.multiply(BigDecimal.valueOf(month.seconds))

            assertThat(fromAmount).isEqualByComparingTo(BigDecimal("3000"))
            // The rate route is out by about a thousandth of a cent. Small, and avoidable.
            assertThat(fromRate).isNotEqualByComparingTo(BigDecimal("3000"))
        }

        @Test
        fun `an empty window is worth nothing`() {
            val instant = Instant.parse("2026-08-15T00:00:00Z")

            assertThat(CashFlowEngine.expectedOver(BigDecimal("3000"), 86_400, Window(instant, instant)))
                .isEqualByComparingTo(BigDecimal.ZERO)
        }
    }

    @Nested
    @DisplayName("days left in the month")
    inner class DaysLeft {

        @Test
        fun `counts from today to the first of next month`() {
            assertThat(CashFlowEngine.daysLeftInMonth(midAugust, kl)).isEqualTo(17L)
        }

        @Test
        fun `is one on the last day`() {
            assertThat(CashFlowEngine.daysLeftInMonth(Instant.parse("2026-08-31T04:00:00Z"), kl))
                .isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("recent months")
    inner class RecentMonths {

        @Test
        fun `newest first, counting back`() {
            val months = CashFlowEngine.recentMonths(midAugust, kl, 3)

            assertThat(months).containsExactly(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 6, 1),
            )
        }
    }
}
