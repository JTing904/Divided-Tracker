package com.dividendstream.app.data.remote

import com.dividendstream.app.domain.AccumulationCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The ledger counter is the shared accumulation calculator fed with ledger parameters.
 *
 * These tests exist to keep it that way. The moment a flow is mapped into a stream that the
 * calculator reads differently from how the server computed it, the figure on screen starts
 * disagreeing with the figure the server returns -- and the counter visibly jumps every time
 * a refresh lands.
 */
class LedgerStreamMappingTest {

    private val monthStart: Instant = Instant.parse("2026-07-31T16:00:00Z")
    private val monthEnd: Instant = Instant.parse("2026-08-31T16:00:00Z")

    private fun flow(
        direction: String = "INCOME",
        amount: String = "3000.00",
        rate: String = "0.001120071685",
        expected: String = "3000.00000000",
        start: Instant? = monthStart,
        end: Instant? = monthEnd,
    ) = CashFlowDto(
        id = "a",
        name = "Salary",
        direction = direction,
        amount = BigDecimal(amount),
        period = "MONTHLY",
        currency = "MYR",
        startsOn = LocalDate.of(2026, 8, 1),
        ratePerSecond = BigDecimal(rate),
        windowStart = start,
        windowEnd = end,
        expectedThisMonth = BigDecimal(expected),
        accruedThisMonth = BigDecimal("0"),
    )

    @Test
    fun `a live flow becomes a stream over its own window`() {
        val stream = flow().toAccumulationStream()!!

        assertEquals(monthStart, stream.start)
        assertEquals(monthEnd, stream.end)
        assertEquals(0, stream.expectedAmount.compareTo(BigDecimal("3000")))
    }

    @Test
    fun `a flow that is not running this month yields no stream at all`() {
        // Not a stream worth zero: a stream with no window would count from the epoch, which
        // clamps to its expected amount and would show a full month's salary immediately.
        assertNull(flow(start = null, end = null).toAccumulationStream())
    }

    @Test
    fun `the counter lands on the declared amount at the end of the month`() {
        val stream = flow().toAccumulationStream()!!

        val atEnd = AccumulationCalculator.accruedAt(stream, monthEnd)

        assertEquals(0, atEnd.compareTo(BigDecimal("3000")))
    }

    @Test
    fun `halfway through the month the counter is halfway`() {
        val stream = flow().toAccumulationStream()!!
        val halfway = monthStart.plusSeconds((monthEnd.epochSecond - monthStart.epochSecond) / 2)

        val accrued = AccumulationCalculator.accruedAt(stream, halfway)

        // Within a millionth of a ringgit of RM1,500 -- see the backend's CashFlowEngineTest
        // for why the interior of the window carries that much and no less.
        assertEquals(
            true,
            accrued.subtract(BigDecimal("1500")).abs() < BigDecimal("0.000001"),
        )
    }

    @Test
    fun `what is left over is income minus outgoings`() {
        val income = listOfNotNull(flow(amount = "3000.00").toAccumulationStream())
        val outgoings = listOfNotNull(
            flow(
                direction = "EXPENSE",
                amount = "1000.00",
                rate = "0.000373357228",
                expected = "1000.00000000",
            ).toAccumulationStream(),
        )

        val net = AccumulationCalculator.totalAccruedAt(income, monthEnd)
            .subtract(AccumulationCalculator.totalAccruedAt(outgoings, monthEnd))

        assertEquals(0, net.compareTo(BigDecimal("2000")))
    }

    @Test
    fun `spending more than you earn counts downwards rather than stopping at zero`() {
        val income = listOfNotNull(
            flow(amount = "300.00", rate = "0.000112007168", expected = "300.00000000")
                .toAccumulationStream(),
        )
        val outgoings = listOfNotNull(
            flow(
                direction = "EXPENSE",
                amount = "800.00",
                rate = "0.000298685783",
                expected = "800.00000000",
            ).toAccumulationStream(),
        )

        val net = AccumulationCalculator.totalAccruedAt(income, monthEnd)
            .subtract(AccumulationCalculator.totalAccruedAt(outgoings, monthEnd))

        assertEquals(0, net.compareTo(BigDecimal("-500")))
    }
}
