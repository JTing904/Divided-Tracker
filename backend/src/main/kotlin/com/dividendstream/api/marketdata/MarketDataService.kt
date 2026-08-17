package com.dividendstream.api.marketdata

import com.dividendstream.api.config.CacheConfig
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * Caching façade over the configured [StockDataProvider].
 *
 * Prices and dividend schedules change on the order of minutes and days respectively, so
 * they are cached rather than fetched per request. The live counter never comes through
 * here at all -- it is derived from stored timestamps -- so no amount of user activity
 * turns into upstream traffic.
 */
@Service
class MarketDataService(private val provider: StockDataProvider) {

    val providerName: String get() = provider.name

    @Cacheable(CacheConfig.STOCK_SEARCH, key = "#query.trim().toLowerCase()")
    fun search(query: String): List<ProviderStock> = provider.search(query)

    @Cacheable(CacheConfig.STOCK_QUOTES, key = "#symbol.toUpperCase()", unless = "#result == null")
    fun quote(symbol: String): ProviderQuote? = provider.latestQuote(symbol)

    @Cacheable(CacheConfig.STOCK_DIVIDENDS, key = "#symbol.toUpperCase()")
    fun dividends(symbol: String): List<ProviderDividend> = provider.dividends(symbol)

    fun findBySymbol(symbol: String): ProviderStock? = provider.findBySymbol(symbol)
}
