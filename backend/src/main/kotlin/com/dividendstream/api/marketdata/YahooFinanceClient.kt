package com.dividendstream.api.marketdata

import com.dividendstream.api.common.UpstreamUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Thin HTTP client for Yahoo Finance's chart and search endpoints.
 *
 * These are the endpoints Yahoo's own website calls. They are not a documented, supported
 * API: there is no contract, no versioning promise and no service level. That is an accepted
 * trade-off for a personal-use app — it is the only free source with usable Bursa Malaysia
 * coverage — but it is the reason every parse below is defensive and every failure degrades
 * rather than propagates.
 *
 * No credentials are involved, so nothing here needs to reach the client applications.
 */
class YahooFinanceClient(
    private val restClient: RestClient,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Raw search hits, unfiltered. */
    fun search(query: String, limit: Int): List<SearchHit> {
        val body = get(
            "/v1/finance/search?q={q}&quotesCount={n}&newsCount=0&listsCount=0",
            query, limit,
        ) ?: return emptyList()

        return body.path("quotes").mapNotNull { node ->
            val symbol = node.path("symbol").asText(null) ?: return@mapNotNull null
            SearchHit(
                symbol = symbol,
                shortName = node.path("shortname").asText(null)
                    ?: node.path("longname").asText(null)
                    ?: symbol,
                exchange = node.path("exchange").asText(null).orEmpty(),
                quoteType = node.path("quoteType").asText(null).orEmpty(),
            )
        }
    }

    /**
     * One call returns the quote and the dividend history together, which is why the chart
     * endpoint is used rather than the quote endpoint — the latter now demands a cookie and
     * crumb handshake, and would double the request count for no extra data.
     */
    fun chart(symbol: String, range: String = "5y"): Chart? {
        val body = get(
            "/v8/finance/chart/{symbol}?range={range}&interval=1d&events=div",
            symbol, range,
        ) ?: return null

        val result = body.path("chart").path("result").firstOrNull() ?: return null
        val meta = result.path("meta")

        val price = meta.path("regularMarketPrice").takeIf { it.isNumber }?.decimalValue()

        val dividends = result.path("events").path("dividends")
            .let { node -> if (node.isObject) node.elements().asSequence().toList() else emptyList() }
            .mapNotNull { node ->
                val amount = node.path("amount").takeIf { it.isNumber }?.decimalValue()
                    ?: return@mapNotNull null
                val epochSeconds = node.path("date").takeIf { it.isNumber }?.asLong()
                    ?: return@mapNotNull null
                // Yahoo dates these events by the ex-dividend date, in exchange local time.
                DividendEvent(
                    exDate = Instant.ofEpochSecond(epochSeconds)
                        .atZone(exchangeZone(meta))
                        .toLocalDate(),
                    amount = amount,
                )
            }
            .sortedBy { it.exDate }

        return Chart(
            symbol = meta.path("symbol").asText(symbol),
            longName = meta.path("longName").asText(null)
                ?: meta.path("shortName").asText(null)
                ?: symbol,
            currency = meta.path("currency").asText("MYR").uppercase(),
            exchangeName = meta.path("fullExchangeName").asText(null)
                ?: meta.path("exchangeName").asText("").orEmpty(),
            price = price,
            priceAsOf = meta.path("regularMarketTime").takeIf { it.isNumber }
                ?.let { Instant.ofEpochSecond(it.asLong()) },
            dividends = dividends,
        )
    }

    private fun exchangeZone(meta: JsonNode): ZoneOffset {
        // gmtoffset is seconds east of UTC. Using it keeps an ex-date that falls near
        // midnight from sliding a day when converted.
        val offsetSeconds = meta.path("gmtoffset").takeIf { it.isNumber }?.asInt() ?: 0
        return runCatching { ZoneOffset.ofTotalSeconds(offsetSeconds) }.getOrDefault(ZoneOffset.UTC)
    }

    private fun get(uriTemplate: String, vararg args: Any): JsonNode? = try {
        restClient.get()
            .uri(uriTemplate, *args)
            .retrieve()
            .body(String::class.java)
            ?.let(mapper::readTree)
    } catch (e: Exception) {
        // A provider outage must not become a 500. Callers fall back to what is already
        // stored, so a stale price is shown rather than an error page.
        log.warn("Yahoo Finance request failed for {}: {}", args.firstOrNull(), e.message)
        throw UpstreamUnavailableException()
    }

    data class SearchHit(
        val symbol: String,
        val shortName: String,
        val exchange: String,
        val quoteType: String,
    )

    data class Chart(
        val symbol: String,
        val longName: String,
        val currency: String,
        val exchangeName: String,
        val price: BigDecimal?,
        val priceAsOf: Instant?,
        val dividends: List<DividendEvent>,
    )

    data class DividendEvent(
        val exDate: LocalDate,
        val amount: BigDecimal,
    )
}
