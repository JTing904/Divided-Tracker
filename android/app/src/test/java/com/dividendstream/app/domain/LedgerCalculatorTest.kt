package com.dividendstream.app.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The ledger, worked out on the device, has to agree with what the server used to say.
 *
 * These are the same situations the backend's own tests cover, asserted against the client's
 * copy of the arithmetic. They are the whole basis for claiming the move is safe: a person who
 * has been reading RM1,335.61 must still read RM1,335.61 afterwards, and "I ported it
 * carefully" is not evidence of that.
 */
class LedgerCalculatorTest {

    private val kl: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

    /** Midday on 15 August 2026 in Kuala Lumpur -- mid-month, so a day view differs. */
    private val now: Instant = LocalDate.of(2026, 8, 15).atTime(12, 0).atZone(kl).toInstant()

    private fun flow(
        id: String = "f1",
        name: String = "Allowance",
        direction: String = "INCOME",
        amount: String = "10.00",
        period: String = "DAILY",
        startsOn: LocalDate = LocalDate.of(2026, 8, 1),
        endsOn: LocalDate? = null,
        arrivesOn: Int? = null,
        arrivesMonth: Int? = null,
    ) = StoredFlow(
        id = id, name = name, direction = direction, amount = BigDecimal(amount),
        period = period, startsOn = startsOn, endsOn = endsOn,
        arrivesOn = arrivesOn, arrivesMonth = arrivesMonth,
    )

    private fun calc(
        stored: StoredLedger,
        period: String = "MONTH",
        browsing: YearMonth? = null,
    ) = LedgerCalculator.calculate(stored, period, browsing, now, kl)

    @Test
    @DisplayName("a daily allowance running all month is worth the whole month")
    fun `daily income covers the month`() {
        val result = calc(StoredLedger(flows = listOf(flow())))

        // 31 days of August at RM10.
        assertThat(result.ledger.plannedIncome).isEqualByComparingTo(BigDecimal("310.00"))
        assertThat(result.ledger.netRatePerSecond).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("a flow that starts today is not paid for the days before it")
    fun `a flow starting today counts only what is left`() {
        val result = calc(StoredLedger(flows = listOf(flow(startsOn = LocalDate.of(2026, 8, 15)))))

        // The 15th to the 31st inclusive: 17 days, not 31. This is the default that used to
        // credit the whole month and invent money nobody had.
        assertThat(result.ledger.plannedIncome).isEqualByComparingTo(BigDecimal("170.00"))
    }

    @Test
    @DisplayName("a raise entered as two dated flows leaves the earlier month alone")
    fun `split flows answer each month with what was true in it`() {
        val stored = StoredLedger(
            flows = listOf(
                flow(id = "old", amount = "10.00", startsOn = LocalDate.of(2026, 7, 1), endsOn = LocalDate.of(2026, 7, 31)),
                flow(id = "new", amount = "17.00", startsOn = LocalDate.of(2026, 8, 1)),
            ),
        )

        // August is the new figure across its 31 days.
        assertThat(calc(stored).ledger.plannedIncome).isEqualByComparingTo(BigDecimal("527.00"))
        // July is still the old one across its 31 days, which is the entire point of splitting.
        val july = calc(stored, browsing = YearMonth.of(2026, 7))
        assertThat(july.ledger.plannedIncome).isEqualByComparingTo(BigDecimal("310.00"))
        assertThat(july.ledger.isBrowsingPast).isTrue()
    }

    @Test
    @DisplayName("a wage is worth nothing until its payday, and all of it after")
    fun `a monthly wage lands on its payday`() {
        val wage = flow(
            name = "Salary", amount = "3000.00", period = "MONTHLY",
            startsOn = LocalDate.of(2026, 1, 1), arrivesOn = 28,
        )
        // The 15th: payday has not come, so nothing has been received this month.
        assertThat(calc(StoredLedger(flows = listOf(wage))).ledger.monthReceivedNet)
            .isEqualByComparingTo(BigDecimal.ZERO)

        val after = LocalDate.of(2026, 8, 29).atTime(12, 0).atZone(kl).toInstant()
        val paid = LedgerCalculator.calculate(StoredLedger(flows = listOf(wage)), "MONTH", null, after, kl)
        assertThat(paid.ledger.monthReceivedNet).isEqualByComparingTo(BigDecimal("3000.00"))
    }

    @Test
    @DisplayName("a yearly bonus arrives in the month it names, not in December")
    fun `a yearly flow pays in its own month`() {
        val bonus = flow(
            name = "Bonus", amount = "1200.00", period = "YEARLY",
            startsOn = LocalDate.of(2026, 1, 1), arrivesOn = 1, arrivesMonth = 3,
        )
        // March has been and gone by August, so it has landed.
        assertThat(calc(StoredLedger(flows = listOf(bonus))).ledger.keptBeforeThisMonth)
            .isEqualByComparingTo(BigDecimal("1200.00"))
    }

    @Test
    @DisplayName("a record dated later this month does not take money out early")
    fun `a future record is projected but not settled`() {
        val stored = StoredLedger(
            flows = listOf(flow()),
            entries = listOf(
                StoredEntry("e1", LocalDate.of(2026, 8, 31), "EXPENSE", BigDecimal("100.00")),
            ),
        )
        val ledger = calc(stored).ledger

        // Counted in the projection for the whole month...
        assertThat(ledger.plannedExpense).isEqualByComparingTo(BigDecimal("100.00"))
        // ...but not taken off what is settled today.
        assertThat(ledger.recordedNet).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("a finished month is banked once, under an id that cannot bank it twice")
    fun `settlement is produced once per fund and month`() {
        val stored = StoredLedger(
            flows = listOf(flow(amount = "10.00", startsOn = LocalDate.of(2026, 7, 1))),
            funds = listOf(
                StoredFund(
                    id = "fund1", name = "Car", percent = BigDecimal("50.00"),
                    createdAt = LocalDate.of(2026, 7, 1).atStartOfDay(kl).toInstant(),
                ),
            ),
        )

        val first = calc(stored)
        assertThat(first.settlements).hasSize(1)
        val banked = first.settlements.single()
        assertThat(banked.settledMonth).isEqualTo("2026-07")
        assertThat(banked.source).isEqualTo(StoredMovement.MONTHLY_SHARE)
        // Half of July's RM310 surplus.
        assertThat(banked.amount).isEqualByComparingTo(BigDecimal("155.00"))
        assertThat(banked.id).isEqualTo("fund1--2026-07")

        // Written, and asked again: nothing further to bank. On the server a unique index did
        // this; here the id does, and the test is what says so.
        val again = calc(stored.copy(movements = first.settlements))
        assertThat(again.settlements).isEmpty()
        assertThat(again.ledger.funds.single().balance).isEqualByComparingTo(BigDecimal("155.00"))
    }

    @Test
    @DisplayName("a fund holds what has been banked, never this month's share")
    fun `funds hold banked money only`() {
        val stored = StoredLedger(
            flows = listOf(flow()),
            funds = listOf(
                StoredFund(
                    id = "fund1", name = "Car", percent = BigDecimal("25.00"),
                    createdAt = LocalDate.of(2026, 8, 1).atStartOfDay(kl).toInstant(),
                ),
            ),
        )
        val fund = calc(stored).ledger.funds.single()

        // Made this month, so nothing has finished and nothing is banked...
        assertThat(fund.balance).isEqualByComparingTo(BigDecimal.ZERO)
        // ...while this month's share is still reported, beside it rather than inside it.
        assertThat(fund.accruedThisMonth).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("switching the screen to today does not make a fund appear to shrink")
    fun `the day view answers the funds from the month`() {
        val stored = StoredLedger(
            flows = listOf(flow(startsOn = LocalDate.of(2026, 6, 1))),
            funds = listOf(
                StoredFund(
                    id = "fund1", name = "Car", percent = BigDecimal("50.00"),
                    createdAt = LocalDate.of(2026, 6, 1).atStartOfDay(kl).toInstant(),
                ),
            ),
        )

        val month = calc(stored, period = "MONTH").ledger.funds.single()
        val day = calc(stored, period = "DAY").ledger.funds.single()

        assertThat(day.balance).isEqualByComparingTo(month.balance)
        assertThat(day.accruedThisMonth).isEqualByComparingTo(month.accruedThisMonth)
    }

    @Test
    @DisplayName("stopping a flow keeps what it earned; removing it takes it away")
    fun `an end date preserves history`() {
        val running = flow(startsOn = LocalDate.of(2026, 7, 1))
        val stopped = running.copy(endsOn = LocalDate.of(2026, 7, 31))

        val kept = calc(StoredLedger(flows = listOf(stopped))).ledger.keptBeforeThisMonth
        assertThat(kept).isEqualByComparingTo(BigDecimal("310.00"))

        // Gone entirely, and July goes with it. This is why deleting asks first.
        val erased = calc(StoredLedger()).ledger.keptBeforeThisMonth
        assertThat(erased).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("the day view narrows the records without changing what they mean")
    fun `the day view shows one day of records`() {
        val stored = StoredLedger(
            flows = listOf(flow()),
            entries = listOf(
                StoredEntry("today", LocalDate.of(2026, 8, 15), "EXPENSE", BigDecimal("9.24")),
                StoredEntry("earlier", LocalDate.of(2026, 8, 3), "EXPENSE", BigDecimal("2.50")),
            ),
        )

        assertThat(calc(stored, period = "MONTH").ledger.entries).hasSize(2)
        val day = calc(stored, period = "DAY").ledger
        assertThat(day.entries).hasSize(1)
        assertThat(day.recordedNet).isEqualByComparingTo(BigDecimal("-9.24"))
        assertThat(day.period).isEqualTo("DAY")
    }
}
