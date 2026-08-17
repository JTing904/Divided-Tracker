package com.dividendstream.desktop

import com.dividendstream.app.AppPaths
import java.net.HttpURLConnection
import java.net.URI

/** What the window shows while the backend is coming up. */
sealed interface BackendState {
    data object Starting : BackendState
    data object Ready : BackendState
    data class Failed(val message: String) : BackendState
}

object BackendBootstrap {

    const val PORT = 8090

    /**
     * Configures the backend for a single-user desktop install and starts it in this JVM.
     *
     * Bound to the loopback interface deliberately: on a phone the backend is somewhere else
     * on the network, but here it exists only to serve this window, and binding 0.0.0.0 would
     * quietly publish one user's portfolio to their entire coffee-shop Wi-Fi.
     *
     * JWT_SECRET is generated per install rather than shipped. A secret baked into a
     * downloadable installer is the same as no secret at all, since every copy would share it.
     */
    fun start() {
        System.setProperty("server.port", PORT.toString())
        System.setProperty("server.address", "127.0.0.1")
        System.setProperty("spring.profiles.active", "desktop")

        // Real prices and real dividend history. The mock provider is a development fixture
        // whose prices and dates are invented, which is not what someone tracking their own
        // portfolio should be shown.
        System.setProperty("MARKET_DATA_PROVIDER", "yahoo")

        // No backfilled history. Adding a holding used to create PAID records for dividend
        // cycles that closed before the user owned a single share, on the assumption they had
        // held it all along. That fills the history screen immediately, which is why it was
        // tempting, but it reports income the user never received — and received income is the
        // one figure in this application that must never be inferred. History now starts the
        // day the holding is added.
        System.setProperty("DIVIDEND_BACKFILL", "false")
        System.setProperty("dividendstream.data-dir", AppPaths.database.toString())
        System.setProperty("JWT_SECRET", InstallSecret.get())

        // Spring Boot installs its own logging; keep its files with the rest of our data
        // rather than in whatever directory the shortcut happened to launch from.
        System.setProperty("logging.file.name", AppPaths.logs.resolve("backend.log").toString())

        DesktopBackendLauncher.run()
    }

    /** Shuts the API and the database down cleanly. Safe to call more than once. */
    fun stop() = DesktopBackendLauncher.stop()

    /** True once the API answers, so the UI knows when to switch away from the splash. */
    fun isUp(): Boolean = runCatching {
        val connection = URI("http://127.0.0.1:$PORT/api/dividends/live").toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.requestMethod = "GET"
        // 401 is the healthy answer here: the endpoint requires a token, so an auth challenge
        // proves the whole chain - Tomcat, security filters, the dispatcher - is serving.
        val code = runCatching { connection.responseCode }.getOrDefault(0)
        connection.disconnect()
        code in 200..499
    }.getOrDefault(false)
}
