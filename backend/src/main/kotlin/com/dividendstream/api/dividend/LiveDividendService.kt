package com.dividendstream.api.dividend

import com.dividendstream.api.common.Money
import com.dividendstream.api.common.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Read side of the dividend engine.
 *
 * Every figure here is computed from stored parameters and the clock. Nothing in this class
 * writes to the database, which is what makes it safe for the dashboard to poll: refreshing
 * the live counter costs one indexed SELECT, never an UPDATE.
 */
@Service
class LiveDividendService(
    private val transactionRepository: DividendTransactionRepository,
    private val dividendRepository: DividendRepository,
    private val transactionService: DividendTransactionService,
    private val clock: Clock,
) {

    /** Delegates; the controller talks to one service for everything dividend-shaped. */
    fun confirmReceived(userId: UUID, transactionId: UUID, receivedOn: java.time.LocalDate) =
        transactionService.confirmReceived(userId, transactionId, receivedOn)

    @Transactional(readOnly = true)
    fun live(userId: UUID): LiveDividendResponse {
        val now = Instant.now(clock)
        val active = transactionRepository
            .findAllByUserIdAndStatusInOrderByPaymentDateAsc(userId, ACTIVE_STATUSES)

        val streams = active.map { it.toStream(now) }

        // Only cycles genuinely mid-window contribute to the headline rate. Counting one
        // that has not reached its ex-date, or has already matured, would show the money
        // growing faster than it actually is.
        val combinedRate = active
            .filter { it.isAccumulatingAt(now) }
            .fold(BigDecimal.ZERO) { sum, it -> sum + it.ratePerSecond }

        val breakdown = DividendAccumulationEngine.rateBreakdown(combinedRate)

        return LiveDividendResponse(
            serverTime = now,
            currency = active.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
            totalExpected = Money.amount(active.fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedAmount }),
            totalAccrued = Money.accrual(streams.fold(BigDecimal.ZERO) { sum, it -> sum + it.accruedAmount }),
            totalReceived = Money.amount(transactionRepository.sumPaidAmountForUser(userId)),
            rate = breakdown.toResponse(),
            activeStockCount = active.map { it.stock.id }.distinct().size,
            nextPayment = active.minByOrNull { it.paymentDate }?.let { describe(it, now) },
            streams = streams,
        )
    }

    @Transactional(readOnly = true)
    fun upcoming(userId: UUID): UpcomingDividendsResponse {
        val now = Instant.now(clock)
        val active = transactionRepository
            .findAllByUserIdAndStatusInOrderByPaymentDateAsc(userId, ACTIVE_STATUSES)
        val cycles = loadCycles(active)

        return UpcomingDividendsResponse(
            serverTime = now,
            currency = active.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
            totalExpected = Money.amount(active.fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedAmount }),
            items = active.map { describe(it, now, cycles[it.dividendId]) },
        )
    }

    @Transactional(readOnly = true)
    fun history(userId: UUID): DividendHistoryResponse {
        val now = Instant.now(clock)
        val today = LocalDate.now(clock)
        val paid = transactionRepository
            .findAllByUserIdAndStatusOrderByPaymentDateDesc(userId, DividendStatus.PAID)
        val cycles = loadCycles(paid)

        val months = paid
            .groupBy { YearMonth.from(it.paymentDate) }
            .toSortedMap(compareByDescending { it })
            .map { (month, items) ->
                MonthlyDividendGroup(
                    month = month.toString(),
                    total = Money.amount(items.sumOfPaid()),
                    items = items.map { describe(it, now, cycles[it.dividendId]) },
                )
            }

        val byStock = paid
            .groupBy { it.stock.id }
            .map { (_, items) ->
                val stock = items.first().stock
                StockDividendTotal(
                    stockId = stock.id,
                    symbol = stock.symbol,
                    companyName = stock.companyName,
                    total = Money.amount(items.sumOfPaid()),
                )
            }
            .sortedByDescending { it.total }

        return DividendHistoryResponse(
            currency = paid.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
            totalReceived = Money.amount(paid.sumOfPaid()),
            receivedThisYear = Money.amount(paid.filter { it.paymentDate.year == today.year }.sumOfPaid()),
            receivedThisMonth = Money.amount(
                paid.filter { YearMonth.from(it.paymentDate) == YearMonth.from(today) }.sumOfPaid(),
            ),
            months = months,
            byStock = byStock,
        )
    }

    @Transactional(readOnly = true)
    fun detail(userId: UUID, transactionId: UUID): DividendResponse {
        val transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
            .orElseThrow { NotFoundException("That dividend was not found.") }
        val cycle = dividendRepository.findById(transaction.dividendId).orElse(null)
        return describe(transaction, Instant.now(clock), cycle)
    }

    private fun loadCycles(transactions: List<DividendTransactionEntity>): Map<UUID, DividendEntity> {
        if (transactions.isEmpty()) return emptyMap()
        return dividendRepository.findAllById(transactions.map { it.dividendId }).associateBy { it.id }
    }

    private fun describe(
        transaction: DividendTransactionEntity,
        now: Instant,
        cycle: DividendEntity? = null,
    ): DividendResponse {
        val resolved = cycle ?: dividendRepository.findById(transaction.dividendId).orElse(null)
        return DividendResponse(
            id = transaction.id,
            stockId = transaction.stock.id,
            symbol = transaction.stock.symbol,
            companyName = transaction.stock.companyName,
            shares = transaction.shares,
            dividendPerShare = transaction.dividendPerShare,
            expectedAmount = transaction.expectedAmount,
            paidAmount = transaction.paidAmount,
            accruedAmount = transaction.accruedAt(now),
            ratePerSecond = transaction.ratePerSecond,
            currency = transaction.currency,
            accumulationStart = transaction.accumulationStart,
            accumulationEnd = transaction.accumulationEnd,
            progress = DividendAccumulationEngine.progressAt(
                transaction.accumulationStart, transaction.accumulationEnd, now,
            ),
            status = transaction.status,
            frequency = resolved?.frequency,
            exDate = resolved?.exDate,
            recordDate = resolved?.recordDate,
            paymentDate = resolved?.actualPaymentDate ?: transaction.paymentDate,
            paymentDateConfirmed = resolved?.actualPaymentDate != null,
            paidAt = transaction.paidAt,
        )
    }

    private fun DividendTransactionEntity.toStream(now: Instant) = LiveStreamResponse(
        transactionId = id,
        stockId = stock.id,
        symbol = stock.symbol,
        companyName = stock.companyName,
        shares = shares,
        dividendPerShare = dividendPerShare,
        expectedAmount = expectedAmount,
        accruedAmount = accruedAt(now),
        ratePerSecond = ratePerSecond,
        accumulationStart = accumulationStart,
        accumulationEnd = accumulationEnd,
        progress = DividendAccumulationEngine.progressAt(accumulationStart, accumulationEnd, now),
        status = status,
        paymentDate = paymentDate,
        currency = currency,
    )

    private fun DividendTransactionEntity.accruedAt(now: Instant): BigDecimal =
        if (status == DividendStatus.CANCELLED) {
            Money.ZERO_ACCRUAL
        } else {
            DividendAccumulationEngine.accruedAt(
                expectedAmount = expectedAmount,
                ratePerSecond = ratePerSecond,
                accumulationStart = accumulationStart,
                accumulationEnd = accumulationEnd,
                at = now,
            )
        }

    private fun DividendTransactionEntity.isAccumulatingAt(now: Instant): Boolean =
        now.isAfter(accumulationStart) && now.isBefore(accumulationEnd) &&
            status != DividendStatus.CANCELLED && status != DividendStatus.PAID

    private fun List<DividendTransactionEntity>.sumOfPaid(): BigDecimal =
        fold(BigDecimal.ZERO) { sum, it -> sum + (it.paidAmount ?: BigDecimal.ZERO) }

    private fun RateBreakdown.toResponse() = RateBreakdownResponse(
        perSecond = perSecond,
        perMinute = perMinute,
        perHour = perHour,
        perDay = perDay,
        perMonth = perMonth,
        perYear = perYear,
    )

    private companion object {
        const val DEFAULT_CURRENCY = "MYR"
        val ACTIVE_STATUSES = listOf(
            DividendStatus.UPCOMING,
            DividendStatus.ACCUMULATING,
            DividendStatus.PAYABLE,
        )
    }
}
