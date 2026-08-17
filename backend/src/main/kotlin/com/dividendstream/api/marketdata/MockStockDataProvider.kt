package com.dividendstream.api.marketdata

import com.dividendstream.api.dividend.DividendFrequency
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Development provider backed by a fixed Bursa Malaysia catalogue.
 *
 * Dividend dates are generated *relative to today* rather than hardcoded, so the app always
 * has cycles that are mid-accumulation and cycles that have already settled -- the live
 * counter and the history screen both have something real to show on a fresh install.
 *
 * Prices are representative but static; this provider is for development only and is
 * replaced by wiring a real [StockDataProvider] and setting MARKET_DATA_PROVIDER.
 */
@Component
@ConditionalOnProperty(
    prefix = "dividend-stream.market-data",
    name = ["provider"],
    havingValue = "mock",
    matchIfMissing = true,
)
class MockStockDataProvider(private val clock: Clock) : StockDataProvider {

    override val name: String = "mock"

    override fun search(query: String, limit: Int): List<ProviderStock> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return CATALOGUE
            .filter { it.matches(needle) }
            .take(limit)
            .map { it.toProviderStock() }
    }

    override fun findBySymbol(symbol: String): ProviderStock? =
        CATALOGUE.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }?.toProviderStock()

    override fun latestQuote(symbol: String): ProviderQuote? =
        CATALOGUE.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }?.let {
            ProviderQuote(it.symbol, it.price, Instant.now(clock))
        }

    override fun dividends(symbol: String): List<ProviderDividend> {
        val listing = CATALOGUE.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
            ?: return emptyList()
        val today = LocalDate.now(clock)

        // One settled cycle (populates history) and one in flight (drives the live counter).
        val settledPayment = today.minusDays(listing.lastPaymentDaysAgo)
        val upcomingPayment = today.plusDays(listing.nextPaymentInDays)

        return listOf(
            listing.cycleEndingOn(settledPayment),
            listing.cycleEndingOn(upcomingPayment),
        )
    }

    private data class Listing(
        val symbol: String,
        val companyName: String,
        val sector: String,
        val price: BigDecimal,
        val dividendPerShare: BigDecimal,
        val frequency: DividendFrequency,
        val lastPaymentDaysAgo: Long,
        val nextPaymentInDays: Long,
        /**
         * Brand and ticker names people actually type. Nobody searches for "Malayan Banking
         * Berhad" -- they search for "Maybank". Real providers index these too.
         */
        val aliases: List<String> = emptyList(),
    ) {
        fun matches(needle: String): Boolean =
            symbol.lowercase().contains(needle) ||
                companyName.lowercase().contains(needle) ||
                aliases.any { it.lowercase().contains(needle) }

        fun toProviderStock() = ProviderStock(
            symbol = symbol,
            companyName = companyName,
            exchange = EXCHANGE,
            currency = CURRENCY,
            sector = sector,
        )

        /** Ex-date sits three weeks before payment, record date two days after ex-date. */
        fun cycleEndingOn(paymentDate: LocalDate): ProviderDividend {
            val exDate = paymentDate.minusDays(21)
            return ProviderDividend(
                dividendPerShare = dividendPerShare,
                currency = CURRENCY,
                frequency = frequency,
                exDate = exDate,
                recordDate = exDate.plusDays(2),
                paymentDate = paymentDate,
            )
        }
    }

    private companion object {
        const val EXCHANGE = "MYX"
        const val CURRENCY = "MYR"

        private fun bd(value: String) = BigDecimal(value)

        /**
         * Payment offsets are staggered so a demo portfolio shows a spread of statuses
         * rather than every stock paying on the same day.
         */
        val CATALOGUE = listOf(
            Listing("1155", "Malayan Banking Berhad", "Financials", bd("10.10"), bd("0.32"), DividendFrequency.SEMI_ANNUAL, 165, 18, listOf("Maybank", "MBB")),
            Listing("1023", "CIMB Group Holdings Berhad", "Financials", bd("6.85"), bd("0.36"), DividendFrequency.SEMI_ANNUAL, 158, 26, listOf("CIMB")),
            Listing("5347", "Tenaga Nasional Berhad", "Utilities", bd("13.90"), bd("0.25"), DividendFrequency.SEMI_ANNUAL, 172, 11, listOf("Tenaga", "TNB")),
            Listing("1295", "Public Bank Berhad", "Financials", bd("4.42"), bd("0.09"), DividendFrequency.SEMI_ANNUAL, 150, 33, listOf("PBB", "Public Bank")),
            Listing("5183", "Petronas Chemicals Group Berhad", "Materials", bd("4.05"), bd("0.13"), DividendFrequency.SEMI_ANNUAL, 161, 22, listOf("PCHEM", "Petronas")),
            Listing("4197", "Sime Darby Berhad", "Industrials", bd("2.20"), bd("0.11"), DividendFrequency.SEMI_ANNUAL, 176, 7, listOf("Sime")),
            Listing("5225", "IHH Healthcare Berhad", "Health Care", bd("6.70"), bd("0.06"), DividendFrequency.ANNUAL, 320, 46, listOf("IHH")),
            Listing("6888", "Axiata Group Berhad", "Telecommunications", bd("2.35"), bd("0.05"), DividendFrequency.ANNUAL, 330, 36, listOf("Axiata")),
            Listing("3182", "Genting Berhad", "Consumer Discretionary", bd("3.15"), bd("0.08"), DividendFrequency.SEMI_ANNUAL, 155, 29, listOf("Genting")),
            Listing("3816", "MISC Berhad", "Energy", bd("7.40"), bd("0.12"), DividendFrequency.QUARTERLY, 84, 9, listOf("MISC")),
            Listing("4707", "Nestle (Malaysia) Berhad", "Consumer Staples", bd("92.00"), bd("1.40"), DividendFrequency.SEMI_ANNUAL, 168, 15, listOf("Nestle")),
            Listing("4863", "Telekom Malaysia Berhad", "Telecommunications", bd("6.55"), bd("0.13"), DividendFrequency.SEMI_ANNUAL, 149, 41, listOf("TM", "Telekom")),
            Listing("1818", "Bursa Malaysia Berhad", "Financials", bd("7.80"), bd("0.18"), DividendFrequency.SEMI_ANNUAL, 163, 24, listOf("Bursa")),
            Listing("6012", "Maxis Berhad", "Telecommunications", bd("3.50"), bd("0.04"), DividendFrequency.QUARTERLY, 79, 14, listOf("Maxis")),
            Listing("5681", "Petronas Dagangan Berhad", "Energy", bd("21.50"), bd("0.25"), DividendFrequency.QUARTERLY, 88, 5, listOf("PETDAG", "Petronas")),
        )
    }
}
