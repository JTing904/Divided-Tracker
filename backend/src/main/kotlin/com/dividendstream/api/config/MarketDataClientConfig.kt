package com.dividendstream.api.config

import com.dividendstream.api.marketdata.YahooFinanceClient
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "dividend-stream.market-data",
    name = ["provider"],
    havingValue = "yahoo",
)
class MarketDataClientConfig {

    @Bean
    fun yahooFinanceClient(properties: MarketDataProperties, mapper: ObjectMapper): YahooFinanceClient {
        val yahoo = properties.yahoo

        val requestFactory = SimpleClientHttpRequestFactory().apply {
            // Bounded, and short: this sits in front of a user waiting on a search box, and
            // an unresponsive upstream must fail fast enough to fall back rather than hang.
            setConnectTimeout(yahoo.requestTimeout)
            setReadTimeout(yahoo.requestTimeout)
        }

        val restClient = RestClient.builder()
            .baseUrl(yahoo.baseUrl)
            .requestFactory(requestFactory)
            // Yahoo's endpoints reject the default Java user agent outright.
            .defaultHeader("User-Agent", USER_AGENT)
            .defaultHeader("Accept", "application/json")
            .build()

        return YahooFinanceClient(restClient, mapper)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
