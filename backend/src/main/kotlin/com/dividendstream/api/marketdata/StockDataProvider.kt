package com.dividendstream.api.marketdata

import com.dividendstream.api.dividend.DividendFrequency
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The seam between this application and whoever supplies market data.
 *
 * Everything upstream of this interface -- Yahoo Finance, Alpha Vantage, a broker feed, the
 * mock -- is replaceable without touching a service, controller or entity. Credentials for
 * real providers live in backend environment variables and are never sent to the app.
 *
 * Implementations must not throw provider-specific exceptions; wrap transport failures in
 * [com.dividendstream.api.common.UpstreamUnavailableException].
 */
interface StockDataProvider {

    /** Identifier persisted on rows this provider supplied, e.g. `mock`. */
    val name: String

    /** Free-text search over symbol and company name. */
    fun search(query: String, limit: Int = 20): List<ProviderStock>

    fun findBySymbol(symbol: String): ProviderStock?

    fun latestQuote(symbol: String): ProviderQuote?

    /** Declared dividend cycles, past and announced-future, for one symbol. */
    fun dividends(symbol: String): List<ProviderDividend>
}

data class ProviderStock(
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String?,
)

data class ProviderQuote(
    val symbol: String,
    val price: BigDecimal,
    val asOf: Instant,
)

data class ProviderDividend(
    val dividendPerShare: BigDecimal,
    val currency: String,
    val frequency: DividendFrequency,
    val exDate: LocalDate,
    val recordDate: LocalDate?,
    val paymentDate: LocalDate,
    /**
     * Overrides the provider name written to `dividends.source`, letting one provider
     * distinguish what it was told from what it inferred. Null means "use the provider name".
     */
    val sourceTag: String? = null,
)
