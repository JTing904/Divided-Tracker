package com.dividendstream.api.ledger

import com.dividendstream.api.common.InvalidRequestException
import com.dividendstream.api.common.Money
import com.dividendstream.api.common.NotFoundException
import com.dividendstream.api.config.LedgerProperties
import com.dividendstream.api.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * The ledger: money in and out that has nothing to do with the market.
 *
 * Reads here are safe to poll, for the same reason the dividend counter is: every accruing
 * figure is computed from stored parameters and the clock, so refreshing costs one indexed
 * SELECT and never an UPDATE.
 *
 * The service keeps two kinds of number strictly apart, and the DTO names say which is which.
 * `planned`/`accrued` come from the recurring flows the person declared -- projections. `actual`
 * comes from the entries they recorded -- facts. They are never added together here, because a
 * person looking at one number has to know which of the two it is.
 */
@Service
class LedgerService(
    private val cashFlowRepository: CashFlowRepository,
    private val entryRepository: LedgerEntryRepository,
    private val fundRepository: FundRepository,
    private val movementRepository: FundMovementRepository,
    private val userRepository: UserRepository,
    private val properties: LedgerProperties,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun ledger(userId: UUID): LedgerResponse {
        val now = Instant.now(clock)
        val zone = properties.zoneId
        val month = CashFlowEngine.monthWindow(now, zone)

        val flows = cashFlowRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
        val described = flows.map { it.describe(month, now, zone) }

        val monthStartDate = LocalDate.ofInstant(month.start, zone)
        val entries = entryRepository
            .findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(
                userId, monthStartDate, monthStartDate.plusMonths(1).minusDays(1),
            )

        val incomeRate = described.sumRate(FlowDirection.INCOME, now)
        val expenseRate = described.sumRate(FlowDirection.EXPENSE, now)
        val netRate = incomeRate.subtract(expenseRate)

        val accruedIncome = described.sumAccrued(FlowDirection.INCOME)
        val accruedExpense = described.sumAccrued(FlowDirection.EXPENSE)
        val netAccrued = accruedIncome.subtract(accruedExpense)
        val actualIncome = entries.sumAmount(FlowDirection.INCOME)
        val actualExpense = entries.sumAmount(FlowDirection.EXPENSE)

        val plannedIncome = described.sumExpected(FlowDirection.INCOME)
        val plannedExpense = described.sumExpected(FlowDirection.EXPENSE)
        val plannedSurplus = plannedIncome.subtract(plannedExpense)

        val funds = fundRepository.findAllByUserIdOrderByPositionAscCreatedAtAsc(userId)
        val allocated = funds.fold(BigDecimal.ZERO) { sum, it -> sum + it.percent }
            .setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
        // One query for every fund's movements rather than one per fund.
        val movements = movementRepository
            .findAllByUserIdOrderByOccurredOnDescCreatedAtDesc(userId)
            .groupBy { it.fundId }
        val describedFunds = funds.map {
            it.describe(netRate, plannedSurplus, netAccrued, movements[it.id].orEmpty(), flows, now, zone)
        }

        return LedgerResponse(
            serverTime = now,
            currency = userRepository.findById(userId).map { it.baseCurrency }.orElse(DEFAULT_CURRENCY),
            month = YearMonth.from(monthStartDate).toString(),
            monthStart = month.start,
            monthEnd = month.end,
            daysLeftInMonth = CashFlowEngine.daysLeftInMonth(now, zone),

            netRatePerSecond = Money.rate(netRate),
            incomeRatePerSecond = Money.rate(incomeRate),
            expenseRatePerSecond = Money.rate(expenseRate),
            rate = CashFlowEngine.rateBreakdown(netRate, now, zone).toResponse(),

            plannedIncome = Money.amount(plannedIncome),
            plannedExpense = Money.amount(plannedExpense),
            accruedIncome = Money.accrual(accruedIncome),
            accruedExpense = Money.accrual(accruedExpense),
            netAccrued = Money.accrual(netAccrued),

            actualIncome = Money.amount(actualIncome),
            actualExpense = Money.amount(actualExpense),
            actualNet = Money.amount(actualIncome.subtract(actualExpense)),

            funds = describedFunds,
            allocatedPercent = allocated,
            unallocatedPercent = HUNDRED.subtract(allocated),
            totalFundBalance = Money.amount(
                describedFunds.fold(BigDecimal.ZERO) { sum, it -> sum + it.balance },
            ),

            flows = described,
            entries = entries.map { it.describe() },
            months = monthlyTotals(userId, now, zone),
        )
    }

    /**
     * Creates or replaces one recurring flow.
     *
     * When the client supplies an id it owns already, this is an update; when it supplies one
     * that does not exist, a create. That is what makes a save sent twice -- a double tap, a
     * retry after a lost reply -- record one flow rather than two. An id belonging to somebody
     * else is a 404, not a takeover.
     */
    @Transactional
    fun saveFlow(userId: UUID, request: SaveCashFlowRequest): CashFlowResponse {
        val zone = properties.zoneId
        val today = LocalDate.now(clock.withZone(zone))
        // Defaults to the first of this month, not to today.
        //
        // Someone entering "RM3,000 a month" on the 29th is describing a salary they have been
        // earning all month, not one starting this afternoon -- and dating it today would show
        // them RM290 for August and quietly understate what they have. The field is on the
        // form, so a person who genuinely started mid-month can say so.
        val startsOn = request.startsOn ?: today.withDayOfMonth(1)
        val endsOn = request.endsOn
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw InvalidRequestException("The end date cannot be before the start date.")
        }

        val existing = request.id?.let { id ->
            cashFlowRepository.findById(id).orElse(null)?.also {
                if (it.userId != userId) throw NotFoundException("That entry was not found.")
            }
        }

        val entity = existing ?: CashFlowEntity(
            id = request.id ?: UUID.randomUUID(),
            userId = userId,
            currency = userRepository.findById(userId).map { it.baseCurrency }.orElse(DEFAULT_CURRENCY),
        )

        entity.name = request.name.trim()
        entity.direction = request.direction
        entity.amount = Money.amount(request.amount)
        entity.period = request.period
        entity.category = request.category?.trim()?.takeIf { it.isNotEmpty() }
        entity.startsOn = startsOn
        entity.endsOn = endsOn

        val saved = cashFlowRepository.save(entity)
        val now = Instant.now(clock)
        return saved.describe(CashFlowEngine.monthWindow(now, zone), now, zone)
    }

    @Transactional
    fun deleteFlow(userId: UUID, id: UUID) {
        val flow = cashFlowRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NotFoundException("That entry was not found.") }
        cashFlowRepository.delete(flow)
    }

    /** Same id semantics as [saveFlow]: supplying one makes the save safe to send twice. */
    @Transactional
    fun saveEntry(userId: UUID, request: SaveLedgerEntryRequest): LedgerEntryResponse {
        val existing = request.id?.let { id ->
            entryRepository.findById(id).orElse(null)?.also {
                if (it.userId != userId) throw NotFoundException("That entry was not found.")
            }
        }

        val entity = existing ?: LedgerEntryEntity(
            id = request.id ?: UUID.randomUUID(),
            userId = userId,
        )

        entity.direction = request.direction
        entity.amount = Money.amount(request.amount)
        entity.occurredOn = request.occurredOn ?: LocalDate.now(clock.withZone(properties.zoneId))
        entity.category = request.category?.trim()?.takeIf { it.isNotEmpty() }
        entity.note = request.note?.trim()?.takeIf { it.isNotEmpty() }

        return entryRepository.save(entity).describe()
    }

    @Transactional
    fun deleteEntry(userId: UUID, id: UUID) {
        val entry = entryRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NotFoundException("That entry was not found.") }
        entryRepository.delete(entry)
    }

    /**
     * Creates or updates one fund, refusing to let the shares exceed the whole.
     *
     * The check counts every *other* fund, so raising an existing one from 20% to 30% is
     * measured against the rest rather than against itself. Allowing the total past 100% would
     * let the screen promise more of a surplus than exists, which is a lie about money rather
     * than an untidy total.
     */
    @Transactional
    fun saveFund(userId: UUID, request: SaveFundRequest): FundResponse {
        val existing = request.id?.let { id ->
            fundRepository.findById(id).orElse(null)?.also {
                if (it.userId != userId) throw NotFoundException("That fund was not found.")
            }
        }

        val others = fundRepository.findAllByUserIdOrderByPositionAscCreatedAtAsc(userId)
            .filter { it.id != existing?.id }
        val percent = request.percent.setScale(PERCENT_SCALE, RoundingMode.HALF_UP)
        val total = others.fold(percent) { sum, it -> sum + it.percent }
        if (total > HUNDRED) {
            val room = HUNDRED.subtract(total.subtract(percent))
            throw InvalidRequestException(
                "Your funds would add up to more than 100%. There is $room% left to allocate.",
            )
        }

        val entity = existing ?: FundEntity(
            id = request.id ?: UUID.randomUUID(),
            userId = userId,
            position = others.size,
        )

        entity.name = request.name.trim()
        entity.percent = percent
        entity.icon = request.icon?.trim()?.takeIf { it.isNotEmpty() }
        request.position?.let { entity.position = it }

        val saved = fundRepository.save(entity)
        // Described against a zero surplus: the caller is about to reload the ledger anyway,
        // and computing one here would mean loading every flow to answer a save.
        return saved.describe(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            movementRepository.findAllByFundIdOrderByOccurredOnDescCreatedAtDesc(saved.id),
            emptyList(), Instant.now(clock), properties.zoneId,
        )
    }

    /**
     * Records money going into or out of a fund.
     *
     * A withdrawal larger than the balance is refused, and the refusal says what is actually
     * there. Allowing it would leave a fund holding a negative amount, which is not a thing
     * that can happen to a pot of money -- it means either a typo or that some deposits were
     * never recorded, and both are better fixed than stored.
     */
    @Transactional
    fun saveFundMovement(
        userId: UUID,
        fundId: UUID,
        request: SaveFundMovementRequest,
    ): FundMovementResponse {
        val fund = fundRepository.findByIdAndUserId(fundId, userId)
            .orElseThrow { NotFoundException("That fund was not found.") }

        val existing = request.id?.let { id ->
            movementRepository.findById(id).orElse(null)?.also {
                if (it.userId != userId) throw NotFoundException("That movement was not found.")
            }
        }

        val amount = Money.amount(request.amount)
        if (request.direction == FundMovementDirection.WITHDRAWAL) {
            val others = movementRepository
                .findAllByFundIdOrderByOccurredOnDescCreatedAtDesc(fund.id)
                .filter { it.id != existing?.id }
            // What is actually available is the plan's accumulated share as well as anything
            // paid in by hand -- otherwise somebody could not spend the money the fund had
            // been filling with all year without first depositing it to themselves.
            val now = Instant.now(clock)
            val zone = properties.zoneId
            val flows = cashFlowRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
            val share = fund.percent.divide(HUNDRED, SHARE_SCALE, RoundingMode.HALF_UP)
            val month = CashFlowEngine.monthWindow(now, zone)
            val available = fund.earmarkedBeforeThisMonth(share, flows, now, zone)
                .add(fund.accruedShareThisMonth(share, flows, month, now, zone))
                .add(others.total(FundMovementDirection.DEPOSIT))
                .subtract(others.total(FundMovementDirection.WITHDRAWAL))
            if (amount > available) {
                throw InvalidRequestException(
                    "There is only ${Money.amount(available).toPlainString()} in ${fund.name}.",
                )
            }
        }

        val entity = existing ?: FundMovementEntity(
            id = request.id ?: UUID.randomUUID(),
            userId = userId,
            fundId = fund.id,
        )

        entity.direction = request.direction
        entity.amount = amount
        entity.occurredOn = request.occurredOn ?: LocalDate.now(clock.withZone(properties.zoneId))
        entity.note = request.note?.trim()?.takeIf { it.isNotEmpty() }

        return movementRepository.save(entity).describe()
    }

    @Transactional
    fun deleteFundMovement(userId: UUID, id: UUID) {
        val movement = movementRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NotFoundException("That movement was not found.") }
        movementRepository.delete(movement)
    }

    @Transactional
    fun deleteFund(userId: UUID, id: UUID) {
        val fund = fundRepository.findByIdAndUserId(id, userId)
            .orElseThrow { NotFoundException("That fund was not found.") }
        fundRepository.delete(fund)
    }

    /**
     * Recorded totals per month, newest first, including months with nothing in them -- a gap
     * in the list would read as missing data rather than as a month nobody wrote anything down.
     */
    private fun monthlyTotals(
        userId: UUID,
        now: Instant,
        zone: java.time.ZoneId,
    ): List<MonthlyLedgerTotal> {
        val months = CashFlowEngine.recentMonths(now, zone, properties.historyMonths)
        if (months.isEmpty()) return emptyList()

        val earliest = months.last()
        val latest = months.first().plusMonths(1).minusDays(1)
        val byMonth = entryRepository
            .findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(userId, earliest, latest)
            .groupBy { YearMonth.from(it.occurredOn) }

        return months.map { first ->
            val rows = byMonth[YearMonth.from(first)].orEmpty()
            val income = rows.sumAmount(FlowDirection.INCOME)
            val expense = rows.sumAmount(FlowDirection.EXPENSE)
            MonthlyLedgerTotal(
                month = YearMonth.from(first).toString(),
                income = Money.amount(income),
                expense = Money.amount(expense),
                net = Money.amount(income.subtract(expense)),
                entryCount = rows.size,
            )
        }
    }

    private fun CashFlowEntity.describe(
        month: Window,
        now: Instant,
        zone: java.time.ZoneId,
    ): CashFlowResponse {
        val projection = CashFlowEngine.project(amount, period, startsOn, endsOn, month, now, zone)
        return CashFlowResponse(
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
            expectedThisMonth = projection.expected,
            accruedThisMonth = projection.accrued,
        )
    }

    /**
     * A fund's share of the surplus.
     *
     * Nothing is allocated out of a deficit. When outgoings exceed income the surplus is
     * negative, and a fund receiving a negative share would read as money being taken *out*
     * of it -- which is not what happened, and not something this screen knows.
     */
    private fun FundEntity.describe(
        netRate: BigDecimal,
        plannedSurplus: BigDecimal,
        netAccrued: BigDecimal,
        movements: List<FundMovementEntity>,
        flows: List<CashFlowEntity>,
        now: Instant,
        zone: java.time.ZoneId,
    ): FundResponse {
        val share = percent.divide(HUNDRED, SHARE_SCALE, RoundingMode.HALF_UP)
        val paidIn = movements.total(FundMovementDirection.DEPOSIT)
        val takenOut = movements.total(FundMovementDirection.WITHDRAWAL)
        fun of(total: BigDecimal, round: (BigDecimal) -> BigDecimal) =
            if (total.signum() <= 0) round(BigDecimal.ZERO) else round(total.multiply(share))

        val fromEarlierMonths = earmarkedBeforeThisMonth(share, flows, now, zone)
        val carriedOver = fromEarlierMonths.add(paidIn).subtract(takenOut)
        val thisMonth = of(netAccrued, Money::accrual)

        return FundResponse(
            id = id,
            name = name,
            percent = percent,
            icon = icon,
            position = position,
            ratePerSecond = of(netRate, Money::rate),
            plannedThisMonth = of(plannedSurplus, Money::amount),
            accruedThisMonth = thisMonth,
            carriedOver = Money.amount(carriedOver),
            earmarkedEarlier = Money.amount(fromEarlierMonths),
            balance = Money.accrual(carriedOver.add(thisMonth)),
            paidIn = Money.amount(paidIn),
            takenOut = Money.amount(takenOut),
            movements = movements.map { it.describe() },
        )
    }

    /**
     * This fund's share of every month that has already finished, since it was created.
     *
     * The plan fills the fund by itself. Making somebody press a button each month to bank
     * their own allocation would leave a fund reading zero for anyone who forgot -- not a
     * truer number, just a less useful one, and not what setting a percentage means.
     *
     * Each past month is recomputed from the flows as they stand, with their start and end
     * dates respected: a month before a salary began contributes nothing, because the flow was
     * not live in it. What this cannot know is that the salary was a different figure back
     * then -- a raise entered today is applied backwards. That is the price of deriving the
     * history rather than storing a total per month, and it is why this is labelled a
     * projection everywhere it appears, beside the withdrawals, which are facts.
     */
    private fun FundEntity.earmarkedBeforeThisMonth(
        share: BigDecimal,
        flows: List<CashFlowEntity>,
        now: Instant,
        zone: java.time.ZoneId,
    ): BigDecimal {
        val thisMonth = LocalDate.ofInstant(now, zone).withDayOfMonth(1)
        var month = LocalDate.ofInstant(createdAt, zone).withDayOfMonth(1)
        var total = BigDecimal.ZERO
        var guard = 0

        while (month.isBefore(thisMonth) && guard++ < MAX_HISTORY_MONTHS) {
            val surplus = surplusOver(CashFlowEngine.monthOf(month, zone), flows, zone)
            // A month that ran a deficit puts nothing aside, and takes nothing back out
            // either: the fund did not pay the rent.
            if (surplus.signum() > 0) total = total.add(surplus.multiply(share))
            month = month.plusMonths(1)
        }
        return total
    }

    /** Income minus outgoings across a whole month, from the flows that were live during it. */
    private fun surplusOver(
        month: Window,
        flows: List<CashFlowEntity>,
        zone: java.time.ZoneId,
    ): BigDecimal {
        // An instant inside the month, so each rate is divided by that month's own length.
        val inside = month.start.plusSeconds(1)
        return flows.fold(BigDecimal.ZERO) { sum, flow ->
            val expected = CashFlowEngine
                .project(flow.amount, flow.period, flow.startsOn, flow.endsOn, month, inside, zone)
                .expected
            if (flow.direction == FlowDirection.INCOME) sum.add(expected) else sum.subtract(expected)
        }
    }

    /** This fund's share of what the plan has put aside so far this month. */
    private fun FundEntity.accruedShareThisMonth(
        share: BigDecimal,
        flows: List<CashFlowEntity>,
        month: Window,
        now: Instant,
        zone: java.time.ZoneId,
    ): BigDecimal {
        val accrued = flows.fold(BigDecimal.ZERO) { sum, flow ->
            val value = CashFlowEngine
                .project(flow.amount, flow.period, flow.startsOn, flow.endsOn, month, now, zone)
                .accrued
            if (flow.direction == FlowDirection.INCOME) sum.add(value) else sum.subtract(value)
        }
        return if (accrued.signum() <= 0) BigDecimal.ZERO else accrued.multiply(share)
    }

    private fun FundMovementEntity.describe() = FundMovementResponse(
        id = id,
        fundId = fundId,
        occurredOn = occurredOn,
        direction = direction,
        amount = amount,
        note = note,
    )

    private fun List<FundMovementEntity>.total(direction: FundMovementDirection): BigDecimal =
        filter { it.direction == direction }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }

    private fun LedgerRateBreakdown.toResponse() = LedgerRateResponse(
        perSecond = perSecond,
        perMinute = perMinute,
        perHour = perHour,
        perDay = perDay,
        perWeek = perWeek,
        perMonth = perMonth,
        perYear = perYear,
    )

    private fun LedgerEntryEntity.describe() = LedgerEntryResponse(
        id = id,
        occurredOn = occurredOn,
        direction = direction,
        amount = amount,
        category = category,
        note = note,
    )

    /**
     * Only flows genuinely running *at this moment* carry a rate into the headline figure.
     *
     * A job that ended on the 10th accrued real money earlier in the month and keeps it in the
     * accrued totals, but it is not still paying per second on the 20th. Counting it would show
     * the money growing faster than it is -- the same rule the dividend counter follows for a
     * cycle that has not reached its ex-date or has already matured.
     */
    private fun List<CashFlowResponse>.sumRate(direction: FlowDirection, at: Instant): BigDecimal =
        filter {
            it.direction == direction &&
                it.windowStart != null && it.windowEnd != null &&
                at.isAfter(it.windowStart) && at.isBefore(it.windowEnd)
        }.fold(BigDecimal.ZERO) { sum, it -> sum + it.ratePerSecond }

    private fun List<CashFlowResponse>.sumAccrued(direction: FlowDirection): BigDecimal =
        filter { it.direction == direction }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it.accruedThisMonth }

    private fun List<CashFlowResponse>.sumExpected(direction: FlowDirection): BigDecimal =
        filter { it.direction == direction }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedThisMonth }

    private fun List<LedgerEntryEntity>.sumAmount(direction: FlowDirection): BigDecimal =
        filter { it.direction == direction }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it.amount }

    private companion object {
        const val DEFAULT_CURRENCY = "MYR"

        /** Percentages are stored and compared at 2dp: 33.33% is expressible, 33.333% is not. */
        const val PERCENT_SCALE = 2

        /** A percentage divided into a fraction needs room, or 33.33% would become 0.33. */
        const val SHARE_SCALE = 10

        val HUNDRED: java.math.BigDecimal = java.math.BigDecimal("100").setScale(PERCENT_SCALE)

        /**
         * How far back a fund's accumulated share is worked out. Ten years is far more than one
         * could plausibly need, and it stops a bad `created_at` turning one read into an
         * unbounded loop.
         */
        const val MAX_HISTORY_MONTHS = 120
    }
}
