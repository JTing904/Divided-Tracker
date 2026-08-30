package com.dividendstream.app.domain

import com.dividendstream.app.core.Money
import com.dividendstream.app.data.remote.CashFlowDto
import com.dividendstream.app.data.remote.FundDto
import com.dividendstream.app.data.remote.FundMovementDto
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LedgerEntryDto
import com.dividendstream.app.data.remote.LedgerRateDto
import com.dividendstream.app.data.remote.MonthlyLedgerTotalDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The whole ledger screen, worked out on the device.
 *
 * This is the read path of the server's LedgerService, moved here and otherwise left alone. It
 * produces the same [LedgerDto] the API used to return, field for field, which is the point:
 * every screen, view model and counter above it carries on unchanged, and the figures a person
 * has been reading do not move because the arithmetic relocated.
 *
 * It is a pure function of what is stored. Nothing here reads a clock of its own, opens a
 * connection, or writes anything -- a month that needs settling is *returned* rather than
 * banked, so the caller decides when to write and the calculation stays testable without a
 * database anywhere near it.
 */
object LedgerCalculator {

    private const val DEFAULT_CURRENCY = "MYR"
    private const val PERCENT_SCALE = 2
    private const val SHARE_SCALE = 10
    private const val MAX_HISTORY_MONTHS = 120
    private const val HISTORY_MONTHS = 12
    private val HUNDRED: BigDecimal = BigDecimal("100").setScale(PERCENT_SCALE)

    /**
     * The ledger, plus any finished month that has not been banked yet.
     *
     * [settlements] are movements the caller should write. They are worked out here because
     * this is where the surplus is known, and written elsewhere because a calculation that
     * writes cannot be run twice safely -- and this one runs on every screen refresh.
     */
    data class Result(
        val ledger: LedgerDto,
        val settlements: List<StoredMovement>,
    )

    fun calculate(
        stored: StoredLedger,
        period: String,
        browsing: YearMonth?,
        now: Instant,
        zone: ZoneId,
    ): Result {
        // Three months' worth of thinking, in two windows -- the server's comment, and it is
        // still the thing most easily got wrong. [thisMonth] is where the funds live, because
        // a percentage is a share of a month's surplus and a fund is a running position rather
        // than something with historical versions. [window] is what the screen is showing.
        val thisMonth = CashFlowEngine.monthWindow(now, zone)
        val today = LocalDate.ofInstant(now, zone)
        val browsed = browsing?.takeIf { it != YearMonth.from(today) }
        val day = period == "DAY"
        val window = when {
            browsed != null -> CashFlowEngine.monthOf(browsed.atDay(1), zone)
            day -> CashFlowEngine.dayWindow(now, zone)
            else -> thisMonth
        }
        // A past month has no "today" in it, so the day view cannot apply to one.
        val effectiveDay = day && browsed == null

        val flows = stored.flows
        val described = flows.map { it.describe(window, now, zone) }

        val windowFrom = LocalDate.ofInstant(window.start, zone)
        val windowTo = LocalDate.ofInstant(window.end.minusSeconds(1), zone)
        val entries = stored.entries
            .filter { !it.occurredOn.isBefore(windowFrom) && !it.occurredOn.isAfter(windowTo) }
            .sortedByDescending { it.occurredOn }

        val incomeRate = described.sumRate("INCOME", now)
        val expenseRate = described.sumRate("EXPENSE", now)
        val netRate = incomeRate.subtract(expenseRate)

        val accruedIncome = described.sumAccrued("INCOME")
        val accruedExpense = described.sumAccrued("EXPENSE")
        val receivedIncome = described.sumReceived("INCOME")
        val receivedExpense = described.sumReceived("EXPENSE")
        val actualIncome = entries.sumAmount("INCOME")
        val actualExpense = entries.sumAmount("EXPENSE")

        // A record dated later in the month has not happened yet. Writing down next Friday's
        // rent in advance is sensible, and it must not take the money out of a fund four days
        // early. The projection below still counts it, which is where it belongs.
        val settledRows = entries.filter { !it.occurredOn.isAfter(today) }
        val recordedNet = settledRows.sumAmount("INCOME").subtract(settledRows.sumAmount("EXPENSE"))

        val netAccrued = accruedIncome.subtract(accruedExpense).add(recordedNet)
        val receivedNet = receivedIncome.subtract(receivedExpense).add(recordedNet)

        val plannedIncome = described.sumExpected("INCOME").add(actualIncome)
        val plannedExpense = described.sumExpected("EXPENSE").add(actualExpense)
        val plannedSurplus = plannedIncome.subtract(plannedExpense)

        // The funds are answered from the month, always. Switching the screen to today must
        // not make a fund appear to shrink.
        val monthly = if (!effectiveDay && browsed == null) {
            MonthFigures(netRate, plannedSurplus, netAccrued, receivedNet)
        } else {
            monthFigures(stored, thisMonth, now, zone)
        }

        val keptBeforeThisMonth = keptBeforeThisMonth(stored, now, zone)

        val funds = stored.funds.sortedWith(compareBy({ it.position }, { it.createdAt }))
        val settlements = pendingSettlements(funds, stored, now, zone)
        val allocated = funds.fold(BigDecimal.ZERO) { sum, it -> sum + it.percent }
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)

        // Already-known settlements count towards what the funds hold on this very refresh,
        // rather than only after the write lands and the screen asks again.
        val movements = (stored.movements + settlements)
            .sortedByDescending { it.occurredOn }
            .groupBy { it.fundId }

        val describedFunds = funds.map {
            it.describe(monthly.netRate, monthly.plannedSurplus, monthly.received, movements[it.id].orEmpty())
        }

        val ledger = LedgerDto(
            serverTime = now,
            currency = stored.currency.ifBlank { DEFAULT_CURRENCY },
            period = if (effectiveDay) "DAY" else "MONTH",
            periodStart = window.start,
            periodEnd = window.end,
            periodLabel = if (effectiveDay) windowFrom.toString() else YearMonth.from(windowFrom).toString(),
            month = YearMonth.from(windowFrom).toString(),
            isBrowsingPast = browsed != null,
            monthStart = thisMonth.start,
            monthEnd = thisMonth.end,
            daysLeftInMonth = CashFlowEngine.daysLeftInMonth(now, zone),

            netRatePerSecond = Money.rate(netRate),
            incomeRatePerSecond = Money.rate(incomeRate),
            expenseRatePerSecond = Money.rate(expenseRate),
            rate = CashFlowEngine.rateBreakdown(netRate, now, zone).toDto(),

            plannedIncome = Money.amount(plannedIncome),
            plannedExpense = Money.amount(plannedExpense),
            accruedIncome = Money.accrual(accruedIncome),
            accruedExpense = Money.accrual(accruedExpense),
            netAccrued = Money.accrual(netAccrued),
            recordedNet = Money.amount(recordedNet),

            actualIncome = Money.amount(actualIncome),
            actualExpense = Money.amount(actualExpense),
            actualNet = Money.amount(recordedNet),

            keptBeforeThisMonth = Money.amount(keptBeforeThisMonth),
            monthNetAccrued = Money.accrual(monthly.netAccrued),
            monthReceivedNet = Money.amount(monthly.received),
            keptSoFar = Money.accrual(keptBeforeThisMonth.add(monthly.netAccrued)),

            funds = describedFunds,
            allocatedPercent = allocated,
            unallocatedPercent = HUNDRED.subtract(allocated),
            totalFundBalance = Money.amount(
                describedFunds.fold(BigDecimal.ZERO) { sum, it -> sum + it.balance },
            ),

            flows = described,
            entries = entries.map { it.toDto() },
            months = monthlyTotals(stored, now, zone),
        )
        return Result(ledger, settlements)
    }

    // --- the month, when the screen is showing something else --------------------

    private data class MonthFigures(
        val netRate: BigDecimal,
        val plannedSurplus: BigDecimal,
        val netAccrued: BigDecimal,
        val received: BigDecimal,
    )

    private fun monthFigures(
        stored: StoredLedger,
        month: com.dividendstream.app.domain.Window,
        now: Instant,
        zone: ZoneId,
    ): MonthFigures {
        val described = stored.flows.map { it.describe(month, now, zone) }
        val from = LocalDate.ofInstant(month.start, zone)
        val to = LocalDate.ofInstant(month.end.minusSeconds(1), zone)
        val today = LocalDate.ofInstant(now, zone)
        val rows = stored.entries.filter {
            !it.occurredOn.isBefore(from) && !it.occurredOn.isAfter(to) && !it.occurredOn.isAfter(today)
        }
        val recorded = rows.sumAmount("INCOME").subtract(rows.sumAmount("EXPENSE"))

        val netRate = described.sumRate("INCOME", now).subtract(described.sumRate("EXPENSE", now))
        val accrued = described.sumAccrued("INCOME").subtract(described.sumAccrued("EXPENSE"))
        val received = described.sumReceived("INCOME").subtract(described.sumReceived("EXPENSE"))

        val all = stored.entries.filter { !it.occurredOn.isBefore(from) && !it.occurredOn.isAfter(to) }
        val planned = described.sumExpected("INCOME").add(all.sumAmount("INCOME"))
            .subtract(described.sumExpected("EXPENSE").add(all.sumAmount("EXPENSE")))

        return MonthFigures(netRate, planned, accrued.add(recorded), received.add(recorded))
    }

    // --- every month that has already finished -----------------------------------

    private fun keptBeforeThisMonth(stored: StoredLedger, now: Instant, zone: ZoneId): BigDecimal {
        val thisMonth = LocalDate.ofInstant(now, zone).withDayOfMonth(1)
        val earliestFlow = stored.flows.minOfOrNull { it.startsOn }
        val earliestEntry = stored.entries
            .filter { it.occurredOn.isBefore(thisMonth) }
            .minOfOrNull { it.occurredOn }
        var month = listOfNotNull(earliestFlow, earliestEntry).minOrNull()?.withDayOfMonth(1)
            ?: return BigDecimal.ZERO
        val floor = thisMonth.minusMonths(MAX_HISTORY_MONTHS.toLong())
        if (month.isBefore(floor)) month = floor

        val recordedByMonth = recordedByMonth(stored, month, thisMonth.minusDays(1))

        var total = BigDecimal.ZERO
        var guard = 0
        while (month.isBefore(thisMonth) && guard++ < MAX_HISTORY_MONTHS) {
            total = total.add(surplusOver(CashFlowEngine.monthOf(month, zone), stored.flows, recordedByMonth, zone))
            month = month.plusMonths(1)
        }
        return total
    }

    private fun recordedByMonth(
        stored: StoredLedger,
        from: LocalDate,
        to: LocalDate,
    ): Map<YearMonth, BigDecimal> = stored.entries
        .filter { !it.occurredOn.isBefore(from) && !it.occurredOn.isAfter(to) }
        .groupBy { YearMonth.from(it.occurredOn) }
        .mapValues { (_, rows) -> rows.sumAmount("INCOME").subtract(rows.sumAmount("EXPENSE")) }

    private fun surplusOver(
        month: com.dividendstream.app.domain.Window,
        flows: List<StoredFlow>,
        recordedByMonth: Map<YearMonth, BigDecimal>,
        zone: ZoneId,
    ): BigDecimal {
        val ended = month.end
        val fromFlows = flows.fold(BigDecimal.ZERO) { sum, flow ->
            val received = CashFlowEngine.receivedOver(
                flow.amount, flow.flowPeriod, flow.startsOn, flow.endsOn, month, ended, zone,
                flow.arrivesOn, flow.arrivesMonth,
            )
            if (flow.direction == "INCOME") sum.add(received) else sum.subtract(received)
        }
        val key = YearMonth.from(LocalDate.ofInstant(month.start, zone))
        return fromFlows.add(recordedByMonth[key] ?: BigDecimal.ZERO)
    }

    // --- banking a month that is over --------------------------------------------

    private fun pendingSettlements(
        funds: List<StoredFund>,
        stored: StoredLedger,
        now: Instant,
        zone: ZoneId,
    ): List<StoredMovement> {
        if (funds.isEmpty()) return emptyList()
        val thisMonth = YearMonth.from(LocalDate.ofInstant(now, zone))
        val already = stored.movements
            .filter { it.settledMonth != null }
            .groupBy { it.fundId }
            .mapValues { (_, rows) -> rows.mapNotNull { it.settledMonth }.toSet() }

        val earliest = funds.minOf { YearMonth.from(LocalDate.ofInstant(it.createdAt, zone)) }
        if (!earliest.isBefore(thisMonth)) return emptyList()
        val recorded = recordedByMonth(stored, earliest.atDay(1), thisMonth.atDay(1).minusDays(1))

        val pending = mutableListOf<StoredMovement>()
        for (fund in funds) {
            val settled = already[fund.id].orEmpty()
            val share = fund.percent.divide(HUNDRED, SHARE_SCALE, RoundingMode.HALF_UP)
            var month = YearMonth.from(LocalDate.ofInstant(fund.createdAt, zone))
            var guard = 0
            while (month.isBefore(thisMonth) && guard++ < MAX_HISTORY_MONTHS) {
                val key = month.toString()
                if (key !in settled) {
                    val surplus = surplusOver(CashFlowEngine.monthOf(month.atDay(1), zone), stored.flows, recorded, zone)
                    val amount = Money.amount(surplus.multiply(share))
                    if (surplus.signum() > 0 && amount.signum() > 0) {
                        pending += StoredMovement(
                            id = StoredMovement.settlementId(fund.id, key),
                            fundId = fund.id,
                            // Dated the day it was banked, which is the day after the month it
                            // banks -- the same day the money stopped being this month's.
                            occurredOn = month.plusMonths(1).atDay(1),
                            direction = StoredMovement.DEPOSIT,
                            amount = amount,
                            note = null,
                            source = StoredMovement.MONTHLY_SHARE,
                            settledMonth = key,
                        )
                    }
                }
                month = month.plusMonths(1)
            }
        }
        return pending
    }

    // --- describing one thing at a time ------------------------------------------

    private fun StoredFlow.describe(month: com.dividendstream.app.domain.Window, now: Instant, zone: ZoneId): CashFlowDto {
        val projection = CashFlowEngine.project(
            amount, flowPeriod, startsOn, endsOn, month, now, zone, arrivesOn, arrivesMonth,
        )
        return CashFlowDto(
            id = id,
            name = name,
            direction = direction,
            amount = amount,
            period = period,
            category = category,
            currency = currency,
            startsOn = startsOn,
            endsOn = endsOn,
            // A flow that is not live this month still reports its rate, so the row can say
            // what it is worth; it just contributes nothing to the totals.
            ratePerSecond = Money.rate(projection.ratePerSecond),
            windowStart = projection.window?.start,
            windowEnd = projection.window?.end,
            arrivesOn = arrivesOn,
            arrivesMonth = arrivesMonth,
            expectedThisMonth = projection.expected,
            accruedThisMonth = projection.accrued,
            receivedThisMonth = projection.received,
        )
    }

    private fun StoredFund.describe(
        netRate: BigDecimal,
        plannedSurplus: BigDecimal,
        netAccrued: BigDecimal,
        movements: List<StoredMovement>,
    ): FundDto {
        val share = percent.divide(HUNDRED, SHARE_SCALE, RoundingMode.HALF_UP)
        val paidIn = movements.total(StoredMovement.DEPOSIT)
        val takenOut = movements.total(StoredMovement.WITHDRAWAL)
        fun of(total: BigDecimal, round: (BigDecimal) -> BigDecimal) =
            if (total.signum() <= 0) round(BigDecimal.ZERO) else round(total.multiply(share))

        // Read back rather than derived. Every finished month since this fund was made has
        // been banked as a movement, so the balance is a sum over rows a person can see.
        val fromEarlierMonths = movements
            .filter { it.source == StoredMovement.MONTHLY_SHARE }
            .fold(BigDecimal.ZERO) { sum, it ->
                if (it.direction == StoredMovement.DEPOSIT) sum.add(it.amount) else sum.subtract(it.amount)
            }
        val carriedOver = paidIn.subtract(takenOut)

        return FundDto(
            id = id,
            name = name,
            percent = percent,
            icon = icon,
            position = position,
            ratePerSecond = of(netRate, Money::rate),
            plannedThisMonth = of(plannedSurplus, Money::amount),
            accruedThisMonth = of(netAccrued, Money::accrual),
            // Banked only. This month's share is reported beside it and deliberately not
            // counted here: one recorded lunch moving two headline figures at once reads as
            // the money being taken twice.
            balance = Money.amount(carriedOver),
            carriedOver = Money.amount(carriedOver),
            earmarkedEarlier = Money.amount(fromEarlierMonths),
            paidIn = Money.amount(paidIn),
            takenOut = Money.amount(takenOut),
            movements = movements.map { it.toDto() },
        )
    }

    private fun monthlyTotals(stored: StoredLedger, now: Instant, zone: ZoneId): List<MonthlyLedgerTotalDto> {
        val months = CashFlowEngine.recentMonths(now, zone, HISTORY_MONTHS)
        if (months.isEmpty()) return emptyList()
        val byMonth = stored.entries.groupBy { YearMonth.from(it.occurredOn) }
        return months.map { first ->
            val rows = byMonth[YearMonth.from(first)].orEmpty()
            val income = rows.sumAmount("INCOME")
            val expense = rows.sumAmount("EXPENSE")
            MonthlyLedgerTotalDto(
                month = YearMonth.from(first).toString(),
                income = Money.amount(income),
                expense = Money.amount(expense),
                net = Money.amount(income.subtract(expense)),
                entryCount = rows.size,
            )
        }
    }

    private fun StoredEntry.toDto() = LedgerEntryDto(id, occurredOn, direction, amount, category, note)

    private fun StoredMovement.toDto() =
        FundMovementDto(id, fundId, occurredOn, direction, amount, note, source, settledMonth)

    private fun LedgerRateBreakdown.toDto() =
        LedgerRateDto(perSecond, perMinute, perHour, perDay, perWeek, perMonth, perYear)

    // --- adding things up ---------------------------------------------------------

    /**
     * Only flows genuinely running *at this moment* carry a rate into the headline figure.
     *
     * A job that ended on the 10th accrued real money earlier in the month and keeps it in the
     * accrued totals, but it is not still paying per second on the 20th.
     */
    private fun List<CashFlowDto>.sumRate(direction: String, at: Instant): BigDecimal =
        filter {
            it.direction == direction &&
                it.windowStart != null && it.windowEnd != null &&
                at.isAfter(it.windowStart) && at.isBefore(it.windowEnd)
        }.fold(BigDecimal.ZERO) { sum, it -> sum + it.ratePerSecond }

    private fun List<CashFlowDto>.sumAccrued(direction: String): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { sum, it -> sum + it.accruedThisMonth }

    private fun List<CashFlowDto>.sumReceived(direction: String): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { sum, it -> sum + it.receivedThisMonth }

    private fun List<CashFlowDto>.sumExpected(direction: String): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedThisMonth }

    private fun List<StoredEntry>.sumAmount(direction: String): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }

    private fun List<StoredMovement>.total(direction: String): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }
}
