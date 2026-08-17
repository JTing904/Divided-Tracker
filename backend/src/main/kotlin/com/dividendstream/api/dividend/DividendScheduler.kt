package com.dividendstream.api.dividend

import com.dividendstream.api.auth.RefreshTokenRepository
import com.dividendstream.api.stock.StockRepository
import com.dividendstream.api.stock.StockService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Background maintenance.
 *
 * Note what is *not* here: nothing recomputes or stores accumulated values. The live figure
 * is derived from timestamps on read, so these jobs only handle genuine state changes --
 * prices moving, dividends being declared, cycles reaching their payment date.
 */
@Component
@ConditionalOnProperty(
    prefix = "dividend-stream.scheduling",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DividendScheduler(
    private val stockRepository: StockRepository,
    private val stockService: StockService,
    private val dividendSyncService: DividendSyncService,
    private val dividendTransactionService: DividendTransactionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Advances entitlements across their lifecycle boundaries and settles matured ones.
     * Both queries are index-backed and touch only rows that actually crossed a boundary.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT30S")
    fun advanceDividendStatuses() {
        runCatching { dividendTransactionService.advanceStatuses(Instant.now(clock)) }
            .onFailure { log.error("Failed to advance dividend statuses", it) }
    }

    /** Refreshes prices and picks up newly declared dividends. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
    @Transactional
    fun refreshMarketData() {
        val stocks = stockRepository.findAll()
        var refreshed = 0
        stocks.forEach { stock ->
            runCatching {
                if (stockService.refreshQuote(stock)) refreshed++
                val cycles = dividendSyncService.syncStock(stock)
                if (cycles.isNotEmpty()) {
                    // A revised or newly declared dividend changes expected amounts and
                    // rates for everyone holding the stock.
                    dividendTransactionService.syncHoldersOf(stock)
                }
            }.onFailure { log.warn("Market data refresh failed for {}", stock.symbol, it) }
        }
        log.debug("Market data refresh complete: {}/{} quotes updated", refreshed, stocks.size)
    }

    /** Expired refresh tokens are dead weight; clear them out nightly. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    fun purgeExpiredRefreshTokens() {
        val removed = refreshTokenRepository.deleteExpiredBefore(Instant.now(clock))
        if (removed > 0) log.info("Purged {} expired refresh tokens", removed)
    }
}
