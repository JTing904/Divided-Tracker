package com.dividendstream.api.portfolio

import com.dividendstream.api.common.InvalidRequestException
import com.dividendstream.api.common.Money
import com.dividendstream.api.common.NotFoundException
import com.dividendstream.api.dividend.DividendEntity
import com.dividendstream.api.dividend.DividendRepository
import com.dividendstream.api.dividend.DividendStatus
import com.dividendstream.api.dividend.DividendSyncService
import com.dividendstream.api.dividend.DividendTransactionRepository
import com.dividendstream.api.dividend.DividendTransactionService
import com.dividendstream.api.marketdata.ProviderDividend
import com.dividendstream.api.stock.StockService
import com.dividendstream.api.stock.dividendYieldPercent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class PortfolioService(
    private val holdingRepository: HoldingRepository,
    private val dividendRepository: DividendRepository,
    private val transactionRepository: DividendTransactionRepository,
    private val stockService: StockService,
    private val dividendSyncService: DividendSyncService,
    private val dividendTransactionService: DividendTransactionService,
    private val purchaseIntentRepository: PurchaseIntentRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun portfolio(userId: UUID): PortfolioResponse {
        val holdings = holdingRepository.findAllByUserIdOrderByCreatedAtAsc(userId)
        if (holdings.isEmpty()) {
            return PortfolioResponse(emptyList(), Money.ZERO_AMOUNT, Money.ZERO_AMOUNT, Money.ZERO_AMOUNT, DEFAULT_CURRENCY)
        }

        // Two batched lookups rather than one pair per holding.
        val today = LocalDate.now(clock)

        // Full history per stock, not just the latest cycle: the yield is computed from a
        // trailing year of actual payments.
        val dividendsByStock = dividendRepository
            .findAllForStocks(holdings.map { it.stock.id })
            .groupBy { it.stock.id }

        val activeByHolding = transactionRepository
            .findAllByUserIdAndStatusInOrderByPaymentDateAsc(userId, ACTIVE_STATUSES)
            .groupBy { it.holdingId }

        val responses = holdings.map { holding ->
            val active = activeByHolding[holding.id].orEmpty()
            holding.toResponse(
                dividendHistory = dividendsByStock[holding.stock.id].orEmpty(),
                today = today,
                expectedDividend = Money.amount(active.fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedAmount }),
                nextPaymentDate = active.minByOrNull { it.paymentDate }?.paymentDate,
            )
        }

        return PortfolioResponse(
            holdings = responses,
            totalCostBasis = Money.amount(responses.sumOfAmounts { it.costBasis }),
            totalMarketValue = Money.amount(responses.sumOfAmounts { it.marketValue ?: BigDecimal.ZERO }),
            totalExpectedDividend = Money.amount(responses.sumOfAmounts { it.expectedDividend }),
            currency = responses.firstOrNull()?.currency ?: DEFAULT_CURRENCY,
        )
    }

    @Transactional
    fun addHolding(userId: UUID, request: CreateHoldingRequest): HoldingResponse {
        // Answered before anything is bought. A queued purchase is retried until a reply
        // arrives, and a reply can be lost after the work is done, so whether this already
        // happened has to be decided from the request rather than from what the client heard.
        val alreadyDone = request.idempotencyKey?.let { key ->
            purchaseIntentRepository.findByIdempotencyKeyAndUserId(key, userId).orElse(null)
        }
        if (alreadyDone != null) {
            val holding = holdingRepository.findByIdAndUserId(alreadyDone.holdingId, userId)
                .orElseThrow { NotFoundException("That holding is no longer in your portfolio.") }
            return describe(userId, holding)
        }

        val stock = stockService.importBySymbol(request.symbol)

        val existing = holdingRepository.findByUserIdAndStockId(userId, stock.id).orElse(null)

        request.manualDividend?.let { manual ->
            if (!manual.paymentDate.isAfter(manual.exDate)) {
                throw InvalidRequestException("Payment date must be after the ex-dividend date.")
            }
            dividendSyncService.recordManualDividend(
                stock,
                ProviderDividend(
                    dividendPerShare = manual.dividendPerShare,
                    currency = stock.currency,
                    frequency = manual.frequency,
                    exDate = manual.exDate,
                    recordDate = manual.recordDate,
                    paymentDate = manual.paymentDate,
                ),
            )
        }

        // Buying more of something already held enlarges the position rather than replacing
        // it, and the resulting average price is computed here. Refusing the request instead --
        // as this did -- left the owner to work the average out by hand and type it in, which
        // is arithmetic no one should be doing about their own money, and wrong forever if
        // they slip. Correcting a mistyped position is a different intent and stays on PUT.
        val holding = if (existing == null) {
            holdingRepository.save(
                HoldingEntity(
                    userId = userId,
                    stock = stock,
                    quantity = Money.quantity(request.quantity),
                    averagePrice = request.averagePrice.setScale(
                        PositionMerge.PRICE_SCALE,
                        java.math.RoundingMode.HALF_UP,
                    ),
                ),
            )
        } else {
            val merged = PositionMerge.merge(
                existingQuantity = existing.quantity,
                existingAveragePrice = existing.averagePrice,
                purchasedQuantity = request.quantity,
                purchasePrice = request.averagePrice,
            )
            existing.quantity = merged.quantity
            existing.averagePrice = merged.averagePrice
            holdingRepository.save(existing)
        }

        // The expected dividend and the per-second rate are both derived from the share count,
        // so a purchase has to re-derive them or the counter keeps running at the old size.
        dividendTransactionService.syncHolding(holding)

        // Recorded in the same transaction as the purchase: either both happen or neither does,
        // so there is never a moment where the shares are bought but the repeat guard is not.
        request.idempotencyKey?.let { key ->
            purchaseIntentRepository.save(
                PurchaseIntentEntity(
                    idempotencyKey = key,
                    userId = userId,
                    holdingId = holding.id,
                    createdAt = Instant.now(clock),
                ),
            )
        }

        return describe(userId, holding)
    }

    @Transactional
    fun updateHolding(userId: UUID, holdingId: UUID, request: UpdateHoldingRequest): HoldingResponse {
        val holding = holdingRepository.findByIdAndUserId(holdingId, userId)
            .orElseThrow { NotFoundException("That holding is not in your portfolio.") }

        holding.quantity = Money.quantity(request.quantity)
        holding.averagePrice = request.averagePrice.setScale(4, java.math.RoundingMode.HALF_UP)
        holdingRepository.save(holding)

        // Resizing the position changes every unsettled expected amount and rate. Settled
        // entitlements are left untouched by the sync.
        dividendTransactionService.syncHolding(holding)

        return describe(userId, holding)
    }

    @Transactional
    fun deleteHolding(userId: UUID, holdingId: UUID) {
        val holding = holdingRepository.findByIdAndUserId(holdingId, userId)
            .orElseThrow { NotFoundException("That holding is not in your portfolio.") }

        dividendTransactionService.detachHolding(holding.id)
        holdingRepository.delete(holding)
    }

    private fun describe(userId: UUID, holding: HoldingEntity): HoldingResponse {
        val history = dividendRepository.findAllByStockIdOrderByPaymentDateDesc(holding.stock.id)
        val active = transactionRepository
            .findAllByUserIdAndStatusInOrderByPaymentDateAsc(userId, ACTIVE_STATUSES)
            .filter { it.holdingId == holding.id }

        return holding.toResponse(
            dividendHistory = history,
            today = LocalDate.now(clock),
            expectedDividend = Money.amount(active.fold(BigDecimal.ZERO) { sum, it -> sum + it.expectedAmount }),
            nextPaymentDate = active.minByOrNull { it.paymentDate }?.paymentDate,
        )
    }

    private fun HoldingEntity.toResponse(
        dividendHistory: List<DividendEntity>,
        expectedDividend: BigDecimal,
        nextPaymentDate: LocalDate?,
        today: LocalDate,
    ): HoldingResponse {
        val latestDividend = dividendHistory.maxByOrNull { it.paymentDate }
        return HoldingResponse(
        id = id,
        stockId = stock.id,
        symbol = stock.symbol,
        companyName = stock.companyName,
        exchange = stock.exchange,
        currency = stock.currency,
        sector = stock.sector,
        quantity = quantity,
        averagePrice = averagePrice,
        currentPrice = stock.lastPrice,
        costBasis = Money.amount(quantity.multiply(averagePrice)),
        marketValue = stock.lastPrice?.let { Money.amount(quantity.multiply(it)) },
        dividendPerShare = latestDividend?.dividendPerShare,
        dividendYieldPercent = dividendYieldPercent(stock.lastPrice, dividendHistory, today),
        expectedDividend = expectedDividend,
        nextPaymentDate = nextPaymentDate,
        )
    }

    private fun List<HoldingResponse>.sumOfAmounts(selector: (HoldingResponse) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { sum, item -> sum + selector(item) }

    private companion object {
        const val DEFAULT_CURRENCY = "MYR"
        val ACTIVE_STATUSES = listOf(
            DividendStatus.UPCOMING,
            DividendStatus.ACCUMULATING,
            DividendStatus.PAYABLE,
        )
    }
}
