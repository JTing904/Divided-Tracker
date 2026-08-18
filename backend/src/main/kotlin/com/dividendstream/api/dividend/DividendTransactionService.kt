package com.dividendstream.api.dividend

import com.dividendstream.api.common.InvalidRequestException
import com.dividendstream.api.common.NotFoundException
import com.dividendstream.api.config.DividendProperties
import com.dividendstream.api.portfolio.HoldingEntity
import com.dividendstream.api.portfolio.HoldingRepository
import com.dividendstream.api.stock.StockEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The window a dividend accrues over. */
data class AccumulationWindow(val start: Instant, val end: Instant)

/**
 * Turns (holding x declared dividend) into the stored accumulation parameters.
 *
 * This is the only place that writes [DividendTransactionEntity] accumulation fields, and it
 * runs when something structural changes -- a holding is added or resized, a dividend is
 * declared or revised. It never runs on a timer faster than a scheduled job, and never per
 * second.
 */
@Service
class DividendTransactionService(
    private val transactionRepository: DividendTransactionRepository,
    private val dividendRepository: DividendRepository,
    private val holdingRepository: HoldingRepository,
    private val properties: DividendProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A dividend accrues across the period it is *earned* over, ending on the payment date:
     * a semi-annual dividend spreads across ~182 days, a quarterly one across ~91. This is
     * what makes the per-second figure meaningful rather than an artefact of how long the
     * registrar takes to pay.
     *
     * Dates are anchored at UTC midnight so the window is identical for every client.
     */
    fun accumulationWindow(dividend: DividendEntity): AccumulationWindow {
        val end = dividend.paymentDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        val start = dividend.paymentDate
            .minusDays(dividend.frequency.accumulationDays)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
        return AccumulationWindow(start, end)
    }

    /** Creates or refreshes every entitlement implied by one holding. */
    @Transactional
    fun syncHolding(holding: HoldingEntity): List<DividendTransactionEntity> {
        val now = Instant.now(clock)
        val today = LocalDate.now(clock)
        val earliestPayment = if (properties.backfillSettledCycles) {
            today.minusDays(properties.backfillWindowDays)
        } else {
            today
        }

        val cycles = dividendRepository.findForStockPayingFrom(holding.stock.id, earliestPayment)
        return cycles.mapNotNull { upsert(holding, it, now) }
    }

    /** Re-runs [syncHolding] for everyone holding this stock, after its dividend data changed. */
    @Transactional
    fun syncHoldersOf(stock: StockEntity): Int =
        holdingRepository.findAllByStockId(stock.id).sumOf { syncHolding(it).size }

    /**
     * Removes entitlements that have not settled when a position is closed -- the user no
     * longer holds the shares. Settled rows are kept: that income really was received.
     */
    @Transactional
    fun detachHolding(holdingId: UUID) {
        val (settled, unsettled) = transactionRepository.findAllByHoldingId(holdingId)
            .partition { it.status == DividendStatus.PAID }

        transactionRepository.deleteAll(unsettled)
        settled.forEach { it.holdingId = null }
        transactionRepository.saveAll(settled)
    }

    /**
     * Moves entitlements through the lifecycle for the given moment. Called by the scheduled
     * job, and inline after a sync so a freshly added holding lands in a correct state.
     */
    @Transactional
    fun advanceStatuses(now: Instant): StatusAdvanceResult {
        val started = transactionRepository.findStartedBefore(now)
        started.forEach {
            it.status = DividendAccumulationEngine.statusAt(it.status, it.accumulationStart, it.accumulationEnd, now)
        }
        transactionRepository.saveAll(started)

        val matured = transactionRepository.findMaturedBefore(
            listOf(DividendStatus.UPCOMING, DividendStatus.ACCUMULATING, DividendStatus.PAYABLE),
            now,
        )
        var settled = 0
        matured.forEach { transaction ->
            // Move to PAYABLE first: a row can mature straight from UPCOMING if the app was
            // offline across the whole window.
            transaction.status = DividendAccumulationEngine.statusAt(
                transaction.status, transaction.accumulationStart, transaction.accumulationEnd, now,
            )
            if (settle(transaction)) settled++
        }
        transactionRepository.saveAll(matured)

        if (started.isNotEmpty() || settled > 0) {
            log.info("Dividend statuses advanced: {} started, {} settled", started.size, settled)
        }
        return StatusAdvanceResult(started = started.size, settled = settled)
    }

    private fun upsert(
        holding: HoldingEntity,
        dividend: DividendEntity,
        now: Instant,
    ): DividendTransactionEntity? {
        val existing = transactionRepository
            .findByUserIdAndDividendId(holding.userId, dividend.id)
            .orElse(null)

        // Settled and cancelled entitlements are historical fact; resizing a position today
        // must not rewrite what was already paid.
        if (existing != null && existing.status == DividendStatus.PAID) return existing
        if (existing != null && existing.status == DividendStatus.CANCELLED) return existing

        val window = accumulationWindow(dividend)
        val expected = DividendAccumulationEngine.expectedAmount(holding.quantity, dividend.dividendPerShare)
        val rate = DividendAccumulationEngine.ratePerSecond(expected, window.start, window.end)

        val transaction = existing ?: DividendTransactionEntity(
            userId = holding.userId,
            stock = holding.stock,
            dividendId = dividend.id,
        )

        transaction.holdingId = holding.id
        transaction.shares = holding.quantity
        transaction.dividendPerShare = dividend.dividendPerShare
        transaction.expectedAmount = expected
        transaction.currency = dividend.currency
        transaction.accumulationStart = window.start
        transaction.accumulationEnd = window.end
        transaction.ratePerSecond = rate
        transaction.paymentDate = dividend.paymentDate
        transaction.status = DividendAccumulationEngine.statusAt(
            transaction.status, window.start, window.end, now,
        )
        settle(transaction)

        return transactionRepository.save(transaction)
    }

    /**
     * Records the day the money genuinely arrived, for a dividend the caller holds.
     *
     * Two things follow, and they are worth separating. For this holder it settles the
     * entitlement against a real date instead of the estimated one, so received income stops
     * being "the date we guessed has passed, so presumably". For the stock it is evidence: the
     * issuer paid that many days after the shares went ex, and every later estimate for it can
     * stop guessing. That is why the date is stored on the shared cycle -- the company paid on
     * that day for everyone who held the shares, not only for whoever happened to report it.
     */
    @Transactional
    fun confirmReceived(userId: UUID, transactionId: UUID, receivedOn: LocalDate) {
        val transaction = transactionRepository.findByIdAndUserId(transactionId, userId)
            .orElseThrow { NotFoundException("That dividend is not in your portfolio.") }

        val dividend = dividendRepository.findById(transaction.dividendId)
            .orElseThrow { NotFoundException("That dividend is no longer available.") }

        if (!receivedOn.isAfter(dividend.exDate)) {
            throw InvalidRequestException(
                "A payment cannot arrive before the shares go ex-dividend on ${dividend.exDate}.",
            )
        }
        if (receivedOn.isAfter(LocalDate.now(clock))) {
            throw InvalidRequestException("That date is in the future.")
        }

        dividend.actualPaymentDate = receivedOn
        dividendRepository.save(dividend)

        transaction.status = DividendStatus.PAID
        transaction.paidAmount = transaction.expectedAmount
        transaction.paidAt = receivedOn.atStartOfDay(ZoneOffset.UTC).toInstant()
        transactionRepository.save(transaction)
    }

    /**
     * Records settlement. [DividendTransactionEntity.paidAmount] is copied from the expected
     * amount because that is all the provider gives us; once a broker feed exists it must be
     * the actual credited figure instead. Estimated and received stay distinct fields either
     * way -- history only ever sums `paidAmount`.
     */
    private fun settle(transaction: DividendTransactionEntity): Boolean {
        if (transaction.status != DividendStatus.PAYABLE) return false
        transaction.status = DividendStatus.PAID
        transaction.paidAmount = transaction.expectedAmount
        // The estimated payment date, because that is all there is until somebody confirms the
        // real one through confirmReceived. Kept distinct in the response so the difference
        // between "the date we estimated has passed" and "the money arrived" stays visible.
        transaction.paidAt = transaction.accumulationEnd
        return true
    }
}

data class StatusAdvanceResult(val started: Int, val settled: Int)
