package com.dividendstream.api.marketdata

import com.dividendstream.api.config.MarketDataProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Live market data from Yahoo Finance.
 *
 * What is genuinely reported: the current price, and each past dividend's ex-date and
 * amount. The payment date is estimated and the record date is unavailable — see
 * [YahooDividendCalendar], which is where that line is drawn and tagged.
 */
@Component
@ConditionalOnProperty(
    prefix = "dividend-stream.market-data",
    name = ["provider"],
    havingValue = "yahoo",
)
class YahooFinanceStockDataProvider(
    private val client: YahooFinanceClient,
    private val properties: MarketDataProperties,
    private val clock: Clock,
) : StockDataProvider {

    override val name: String = "yahoo"

    override fun search(query: String, limit: Int): List<ProviderStock> =
        client.search(query, limit)
            .asSequence()
            .filter { it.quoteType.equals("EQUITY", ignoreCase = true) }
            // Restricted to the configured exchange so a search for "maybank" does not offer
            // the Jakarta and Munich listings alongside the Bursa one, which are different
            // securities paying different dividends.
            .filter { properties.yahoo.exchange.isBlank() || it.exchange.equals(properties.yahoo.exchange, true) }
            .take(limit)
            .map { hit ->
                ProviderStock(
                    symbol = localSymbol(hit.symbol),
                    companyName = hit.shortName,
                    exchange = properties.yahoo.exchangeLabel,
                    currency = properties.yahoo.currency,
                    sector = null, // The search endpoint does not carry it.
                )
            }
            .toList()

    override fun findBySymbol(symbol: String): ProviderStock? =
        client.chart(vendorSymbol(symbol), range = "1mo")?.let { chart ->
            ProviderStock(
                symbol = localSymbol(chart.symbol),
                companyName = chart.longName,
                // Always the configured label, never Yahoo's display name ("Kuala Lumpur").
                // Exchange plus symbol is this stock's identity, so if this path returned a
                // different string from search(), importing would create a second row for the
                // same security -- and the holding pointing at the first row would keep its
                // stale price forever.
                exchange = properties.yahoo.exchangeLabel,
                currency = chart.currency,
                sector = null,
            )
        }

    override fun latestQuote(symbol: String): ProviderQuote? =
        client.chart(vendorSymbol(symbol), range = "1mo")?.let { chart ->
            val price = chart.price ?: return null
            ProviderQuote(
                symbol = localSymbol(chart.symbol),
                price = price,
                asOf = chart.priceAsOf ?: Instant.now(clock),
            )
        }

    override fun dividends(symbol: String): List<ProviderDividend> {
        val chart = client.chart(vendorSymbol(symbol), range = properties.yahoo.historyRange)
            ?: return emptyList()

        return YahooDividendCalendar.toCycles(
            events = chart.dividends,
            currency = chart.currency,
            today = LocalDate.now(clock),
            paymentLagDays = properties.yahoo.paymentLagDays,
        )
    }

    /**
     * Bursa codes are bare numbers ("1155"); Yahoo needs the exchange suffix ("1155.KL").
     * Symbols that already carry a suffix are left alone, so a US ticker still works if the
     * exchange filter is relaxed.
     */
    private fun vendorSymbol(symbol: String): String {
        val trimmed = symbol.trim().uppercase()
        val suffix = properties.yahoo.symbolSuffix
        return if (suffix.isBlank() || trimmed.contains('.')) trimmed else "$trimmed$suffix"
    }

    /** The inverse: what the rest of the application and the user call this stock. */
    private fun localSymbol(vendorSymbol: String): String {
        val suffix = properties.yahoo.symbolSuffix
        return if (suffix.isNotBlank() && vendorSymbol.endsWith(suffix, ignoreCase = true)) {
            vendorSymbol.dropLast(suffix.length).uppercase()
        } else {
            vendorSymbol.uppercase()
        }
    }
}
