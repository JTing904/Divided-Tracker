package com.dividendstream.api.marketdata

import com.dividendstream.api.dividend.DividendFrequency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class YahooDividendCalendarTest {

    private fun event(date: String, amount: String) =
        YahooFinanceClient.DividendEvent(LocalDate.parse(date), BigDecimal(amount))

    @Test
    @DisplayName("infers semi-annual from Maybank's real ex-date history")
    fun `infers semi annual`() {
        val exDates = listOf("2024-09-11", "2025-03-12", "2025-09-11", "2026-03-12").map(LocalDate::parse)
        assertThat(YahooDividendCalendar.inferFrequency(exDates)).isEqualTo(DividendFrequency.SEMI_ANNUAL)
    }

    @Test
    fun `infers quarterly and monthly and annual from spacing`() {
        val quarterly = (0..4).map { LocalDate.parse("2025-01-15").plusMonths(it * 3L) }
        val monthly = (0..5).map { LocalDate.parse("2025-01-15").plusMonths(it.toLong()) }
        val annual = (0..2).map { LocalDate.parse("2023-06-01").plusYears(it.toLong()) }

        assertThat(YahooDividendCalendar.inferFrequency(quarterly)).isEqualTo(DividendFrequency.QUARTERLY)
        assertThat(YahooDividendCalendar.inferFrequency(monthly)).isEqualTo(DividendFrequency.MONTHLY)
        assertThat(YahooDividendCalendar.inferFrequency(annual)).isEqualTo(DividendFrequency.ANNUAL)
    }

    @Test
    @DisplayName("a lone special dividend does not drag a semi-annual payer to quarterly")
    fun `median ignores one off spacing`() {
        // Regular March/September cycle plus one special dividend a fortnight after a regular.
        val exDates = listOf(
            "2024-03-12", "2024-03-26", "2024-09-11", "2025-03-12", "2025-09-11",
        ).map(LocalDate::parse)

        assertThat(YahooDividendCalendar.inferFrequency(exDates)).isEqualTo(DividendFrequency.SEMI_ANNUAL)
    }

    @Test
    @DisplayName("too little history assumes the longest period, understating the rate")
    fun `single observation falls back to annual`() {
        assertThat(YahooDividendCalendar.inferFrequency(listOf(LocalDate.parse("2026-03-12"))))
            .isEqualTo(DividendFrequency.ANNUAL)
        assertThat(YahooDividendCalendar.inferFrequency(emptyList()))
            .isEqualTo(DividendFrequency.ANNUAL)
    }

    @Test
    fun `reported cycles keep the real ex-date and carry no record date`() {
        val cycles = YahooDividendCalendar.toCycles(
            events = listOf(event("2025-09-11", "0.30"), event("2026-03-12", "0.33")),
            currency = "MYR",
            today = LocalDate.parse("2026-08-17"),
            paymentLagDays = 30,
        )

        val reported = cycles.filter { it.sourceTag == YahooDividendCalendar.SOURCE_REPORTED }
        assertThat(reported).hasSize(2)
        assertThat(reported.map { it.exDate })
            .containsExactly(LocalDate.parse("2025-09-11"), LocalDate.parse("2026-03-12"))
        assertThat(reported.map { it.paymentDate })
            .containsExactly(LocalDate.parse("2025-10-11"), LocalDate.parse("2026-04-11"))
        assertThat(reported).allMatch { it.recordDate == null }
    }

    @Test
    @DisplayName("projects one upcoming cycle so the live counter has something to accumulate")
    fun `projects the next cycle`() {
        val cycles = YahooDividendCalendar.toCycles(
            events = listOf(event("2025-09-11", "0.30"), event("2026-03-12", "0.33")),
            currency = "MYR",
            today = LocalDate.parse("2026-08-17"),
            paymentLagDays = 30,
        )

        val projected = cycles.single { it.sourceTag == YahooDividendCalendar.SOURCE_PROJECTED }
        // 2026-03-12 + 182 days, then the payment lag.
        assertThat(projected.exDate).isEqualTo(LocalDate.parse("2026-09-10"))
        assertThat(projected.paymentDate).isEqualTo(LocalDate.parse("2026-10-10"))
        assertThat(projected.dividendPerShare).isEqualByComparingTo("0.33")
    }

    @Test
    @DisplayName("does not project when the reported history already reaches the future")
    fun `no projection when history is current`() {
        val cycles = YahooDividendCalendar.toCycles(
            events = listOf(event("2026-03-12", "0.33"), event("2026-09-10", "0.34")),
            currency = "MYR",
            today = LocalDate.parse("2026-08-17"),
            paymentLagDays = 30,
        )

        assertThat(cycles).noneMatch { it.sourceTag == YahooDividendCalendar.SOURCE_PROJECTED }
    }

    @Test
    @DisplayName("does not project a payment that would already be in the past")
    fun `no stale projection`() {
        // Last dividend two years ago: the projected cycle would pay long before today.
        val cycles = YahooDividendCalendar.toCycles(
            events = listOf(event("2023-03-12", "0.30"), event("2023-09-11", "0.31")),
            currency = "MYR",
            today = LocalDate.parse("2026-08-17"),
            paymentLagDays = 30,
        )

        assertThat(cycles).noneMatch { it.sourceTag == YahooDividendCalendar.SOURCE_PROJECTED }
    }

    @Test
    fun `no events yields no cycles`() {
        assertThat(
            YahooDividendCalendar.toCycles(emptyList(), "MYR", LocalDate.parse("2026-08-17"), 30),
        ).isEmpty()
    }

    @Test
    @DisplayName("every cycle satisfies the database's payment-after-ex constraint")
    fun `payment always after ex date`() {
        val cycles = YahooDividendCalendar.toCycles(
            events = listOf(event("2025-09-11", "0.30"), event("2026-03-12", "0.33")),
            currency = "MYR",
            today = LocalDate.parse("2026-08-17"),
            paymentLagDays = 0, // even a nonsensical lag must not violate the constraint
        )

        assertThat(cycles).allMatch { it.paymentDate.isAfter(it.exDate) }
    }
}
