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

@ConfigurationProperties(prefix = "dividend-stream.google")
data class GoogleProperties(
    /**
     * The *Web* OAuth client ID, which is what Android signs in through.
     *
     * Counter-intuitive and a common stumbling block: the Android client ID exists to bind the
     * app's signing certificate to the project and is never sent anywhere. The token Credential
     * Manager returns carries this one as its audience.
     */
    val webClientId: String = "",

    /** The desktop OAuth client. The ID is public; it travels in the browser's address bar. */
    val desktopClientId: String = "",

    /**
     * The desktop client secret, which is why the desktop app sends its authorisation code here
     * rather than redeeming it itself. Installed applications cannot keep a secret, and this
     * project's rule is that third-party credentials live on the backend.
     */
    val desktopClientSecret: String = "",

    /**
     * Audiences to accept beyond the two client IDs above. Almost never needed; it exists so a
     * client ID can be rotated without a moment where neither the old nor the new one works.
     */
    val extraAudiences: List<String> = emptyList(),

    val tokenUri: String = "https://oauth2.googleapis.com/token",
) {
    /**
     * Every OAuth client ID allowed to appear in a token's `aud` claim.
     *
     * Derived rather than configured. The two client IDs are already here, and asking an
     * operator to list them again in a third variable buys nothing and invites the one mistake
     * nothing catches: omit one and that platform alone fails to sign in, with no sign of it
     * until somebody presses the button.
     *
     * Checking the audience at all is what stops an ID token minted for some *other*
     * application -- a token its developer can read -- from being replayed here as a login.
     */
    val allowedAudiences: List<String>
        get() = (listOf(webClientId, desktopClientId) + extraAudiences)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    val isConfigured: Boolean get() = allowedAudiences.isNotEmpty()
    val isDesktopConfigured: Boolean
        get() = isConfigured && desktopClientId.isNotBlank() && desktopClientSecret.isNotBlank()
}

@ConfigurationProperties(prefix = "dividend-stream.release")
data class ReleaseProperties(
    /**
     * The newest client release available to download.
     *
     * Configured rather than derived, because the backend cannot know what has been published:
     * a client release exists on GitHub, on its own schedule, and this is the operator saying
     * so. Blank means "no opinion", and a client that asks is told nothing rather than being
     * told it is up to date -- a silence a client can distinguish from an answer.
     */
    val latestClient: String = "",

    /**
     * The oldest client release still able to talk to this backend.
     *
     * Only raise this for a change a client genuinely cannot survive, because raising it locks
     * anyone below it out of their own portfolio until they update.
     */
    val minimumClient: String = "",

    /**
     * Identifies the running build. Render exposes the deployed SHA as RENDER_GIT_COMMIT; other
     * hosts have their own name for it, and locally there is none.
     */
    val commit: String = "",
)
