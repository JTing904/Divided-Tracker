package com.dividendstream.api.config

import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Configuration

/**
 * Caching is enabled through Spring's [org.springframework.cache.CacheManager] abstraction
 * only. Which backend serves it -- Caffeine locally, Redis in production -- is decided by
 * `spring.cache.type`, so no application code changes when the backend changes.
 */
@Configuration
@EnableCaching
class CacheConfig {
    companion object {
        const val STOCK_QUOTES = "stockQuotes"
        const val STOCK_SEARCH = "stockSearch"
        const val STOCK_DIVIDENDS = "stockDividends"
    }
}
