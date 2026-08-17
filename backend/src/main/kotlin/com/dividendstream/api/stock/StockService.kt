package com.dividendstream.api.stock

import com.dividendstream.api.common.NotFoundException
import com.dividendstream.api.dividend.DividendEntity
import com.dividendstream.api.dividend.DividendRepository
import com.dividendstream.api.dividend.DividendSyncService
import com.dividendstream.api.marketdata.MarketDataService
import com.dividendstream.api.marketdata.ProviderStock
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

@Service
class StockService(
    private val stockRepository: StockRepository,
    private val dividendRepository: DividendRepository,
    private val marketDataService: MarketDataService,
    private val dividendSyncService: DividendSyncService,
    private val clock: Clock,
) {

    /**
     * Searches upstream and mirrors the results locally, so a stock the user picks already
     * exists by the time they submit the holding. Falls back to the local table when the
     * provider is unreachable or returns nothing.
     */
    @Transactional
    fun search(query: String): List<StockSummaryResponse> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()

        val upstream = runCatching { marketDataService.search(trimmed) }.getOrDefault(emptyList())
        if (upstream.isEmpty()) {
            return stockRepository.search(trimmed, PageRequest.of(0, SEARCH_LIMIT)).map { it.toSummary() }
        }
        return upstream.map { upsertFromProvider(it).toSummary() }
    }

    @Transactional(readOnly = true)
    fun listKnown(): List<StockSummaryResponse> =
        stockRepository.findAll().sortedBy { it.companyName }.map { it.toSummary() }

    /**
     * Resolves a symbol to a locally stored stock, importing it from the provider on first
     * use and pulling its dividend schedule at the same time.
     */
    @Transactional
    fun importBySymbol(symbol: String): StockEntity {
        val normalised = symbol.trim()
        val existing = stockRepository.findBySymbolIgnoreCase(normalised).firstOrNull()
        if (existing != null) {
            dividendSyncService.syncStock(existing)
            return existing
        }

        val providerStock = marketDataService.findBySymbol(normalised)
            ?: throw NotFoundException("We could not find a stock with symbol \"$symbol\".")

        val stock = upsertFromProvider(providerStock)
        dividendSyncService.syncStock(stock)
        return stock
    }

    @Transactional
    fun detail(symbol: String): StockDetailResponse {
        val stock = importBySymbol(symbol)
        val history = dividendRepository.findAllByStockIdOrderByPaymentDateDesc(stock.id)
        return stock.toDetail(history, LocalDate.now(clock))
    }

    @Transactional
    fun upsertFromProvider(providerStock: ProviderStock): StockEntity {
        val stock = stockRepository
            .findByExchangeAndSymbol(providerStock.exchange, providerStock.symbol)
            .orElseGet { StockEntity(symbol = providerStock.symbol, exchange = providerStock.exchange) }

        stock.companyName = providerStock.companyName
        stock.currency = providerStock.currency
        stock.sector = providerStock.sector

        marketDataService.quote(providerStock.symbol)?.let { quote ->
            stock.lastPrice = quote.price
            stock.priceUpdatedAt = quote.asOf
        }

        return stockRepository.save(stock)
    }

    /** Refreshes the cached price for one stock. Driven by the scheduled job, not by requests. */
    @Transactional
    fun refreshQuote(stock: StockEntity): Boolean {
        val quote = marketDataService.quote(stock.symbol) ?: return false
        stock.lastPrice = quote.price
        stock.priceUpdatedAt = Instant.now(clock)
        stockRepository.save(stock)
        return true
    }

    private companion object {
        const val MIN_QUERY_LENGTH = 1
        const val SEARCH_LIMIT = 20
    }
}

fun StockEntity.toSummary() = StockSummaryResponse(
    id = id,
    symbol = symbol,
    companyName = companyName,
    exchange = exchange,
    currency = currency,
    sector = sector,
    lastPrice = lastPrice,
)

fun StockEntity.toDetail(history: List<DividendEntity>, today: LocalDate): StockDetailResponse {
    // The headline dividend facts still describe the most recent cycle; only the yield looks
    // across the whole trailing year.
    val latestDividend = history.maxByOrNull { it.paymentDate }
    return StockDetailResponse(
    id = id,
    symbol = symbol,
    companyName = companyName,
    exchange = exchange,
    currency = currency,
    sector = sector,
    lastPrice = lastPrice,
    priceUpdatedAt = priceUpdatedAt,
    dividendPerShare = latestDividend?.dividendPerShare,
    dividendYieldPercent = dividendYieldPercent(lastPrice, history, today),
    dividendFrequency = latestDividend?.frequency?.name,
    exDate = latestDividend?.exDate,
    recordDate = latestDividend?.recordDate,
        nextPaymentDate = latestDividend?.paymentDate,
    )
}

/**
 * Dividend yield from the last twelve months of dividends the company actually paid.
 *
 * The obvious shortcut -- take the most recent declaration and multiply it by the payment
 * frequency -- overstates or understates almost every real payer, because dividends vary
 * between cycles and special dividends exist. Summing what was actually paid over a trailing
 * year is what a yield is normally understood to mean.
 *
 * Projected cycles are excluded: they are this application's own guess at a future payment,
 * and folding a guess into a published yield would launder it into a fact.
 *
 * Falls back to annualising the latest declaration when there is not yet a year of history,
 * which is the best available answer for a newly listed or newly tracked stock.
 */
fun dividendYieldPercent(
    price: BigDecimal?,
    history: List<DividendEntity>,
    today: LocalDate,
): BigDecimal? {
    if (price == null || price.signum() <= 0) return null

    val oneYearAgo = today.minusDays(365)
    val trailing = history.filter {
        it.source != PROJECTED_SOURCE && it.exDate.isAfter(oneYearAgo) && !it.exDate.isAfter(today)
    }

    val annualised = if (trailing.isNotEmpty()) {
        trailing.fold(BigDecimal.ZERO) { sum, cycle -> sum + cycle.dividendPerShare }
    } else {
        val latest = history.filter { it.source != PROJECTED_SOURCE }.maxByOrNull { it.paymentDate }
            ?: return null
        val periodsPerYear = BigDecimal(365)
            .divide(BigDecimal(latest.frequency.accumulationDays), 8, RoundingMode.HALF_UP)
        latest.dividendPerShare.multiply(periodsPerYear)
    }

    if (annualised.signum() <= 0) return null

    return annualised.divide(price, 6, RoundingMode.HALF_UP)
        .multiply(BigDecimal(100))
        .setScale(2, RoundingMode.HALF_UP)
}

private const val PROJECTED_SOURCE = "yahoo-projected"
