package com.dividendstream.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "dividend-stream.jwt")
data class JwtProperties(
    /** HMAC signing secret, at least 32 characters. Supplied via the JWT_SECRET env var. */
    val secret: String = "",
    val issuer: String = "dividend-stream",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
)

@ConfigurationProperties(prefix = "dividend-stream.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList(),
)

@ConfigurationProperties(prefix = "dividend-stream.market-data")
data class MarketDataProperties(
    /** Selects the [com.dividendstream.api.marketdata.StockDataProvider] implementation. */
    val provider: String = "mock",
    val quoteCacheTtl: Duration = Duration.ofMinutes(5),
    val yahoo: YahooProperties = YahooProperties(),
)

/** Settings for the Yahoo Finance provider. No credentials: the endpoints are unauthenticated. */
data class YahooProperties(
    val baseUrl: String = "https://query2.finance.yahoo.com",
    /** Appended to bare Bursa codes: 1155 -> 1155.KL. Blank disables the mapping. */
    val symbolSuffix: String = ".KL",
    /** Yahoo's code for the exchange to keep in search results. Blank keeps all of them. */
    val exchange: String = "KLS",
    val exchangeLabel: String = "MYX",
    val currency: String = "MYR",
    /**
     * Days from ex-date to payment, used only because Yahoo does not report payment dates.
     * Bursa issuers usually pay within a month of the shares going ex.
     */
    val paymentLagDays: Long = 30,
    val historyRange: String = "5y",
    val requestTimeout: Duration = Duration.ofSeconds(10),
)

@ConfigurationProperties(prefix = "dividend-stream.registration")
data class RegistrationProperties(
    /**
     * When set, a matching code must accompany every registration.
     *
     * This exists to stop an open endpoint from being scripted: the storage one account uses
     * is trivial, but every request wakes the database, and a registration loop would burn a
     * month of free-tier compute in an afternoon. Blank leaves registration open.
     */
    val inviteCode: String = "",
)

@ConfigurationProperties(prefix = "dividend-stream.rate-limit")
data class RateLimitProperties(
    val authRequestsPerMinute: Int = 20,
)
