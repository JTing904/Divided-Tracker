package com.dividendstream.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stand-in for the class the Android Gradle plugin generates.
 *
 * The desktop app talks to a backend inside its own process, so the address is a constant
 * rather than a build flag. It is still routed through the same [NetworkModule] parameter
 * the Android app uses, so nothing downstream knows the difference.
 */
object BuildConfig {
    /**
     * Where the API lives. Loopback when this machine runs its own backend, otherwise the
     * shared server. Resolved once at startup by [DesktopSettings].
     */
    val API_BASE_URL: String get() = DesktopSettings.backendUrl

    val DEBUG: Boolean = System.getProperty("dividendstream.debug") == "true"
}

/**
 * Chooses between running everything on this machine and talking to a shared server.
 *
 * Local mode is the default and needs no configuration: the app starts its own PostgreSQL
 * and API, and the data never leaves the machine. Pointing `backend.url` at a hosted server
 * instead is what lets a phone and a desktop see the same portfolio — the database is not
 * contacted directly from here, because that would require shipping its password inside the
 * installer, where anyone could read it.
 *
 * Read from `%LOCALAPPDATA%\DividendStream\config.properties`:
 *
 *     backend.url=https://dividend-stream.example.com/
 */
object DesktopSettings {

    private const val LOCAL_URL = "http://127.0.0.1:8090/"

    private val configured: String? by lazy {
        val file = AppPaths.root.resolve("config.properties")
        runCatching {
            if (!Files.exists(file)) return@runCatching null
            java.util.Properties()
                .apply { Files.newInputStream(file).use { load(it) } }
                .getProperty("backend.url")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                // Retrofit requires the trailing slash.
                ?.let { if (it.endsWith("/")) it else "$it/" }
        }.getOrNull()
    }

    /** True when this machine should start its own database and API. */
    val runsOwnBackend: Boolean get() = configured == null

    val backendUrl: String get() = configured ?: LOCAL_URL
}

/**
 * Where the app keeps its data on this machine.
 *
 * Everything lives under one directory so uninstalling is a single delete, and so the
 * database survives an application upgrade: %LOCALAPPDATA%\DividendStream on Windows, and
 * the platform equivalents elsewhere.
 */
object AppPaths {

    val root: Path by lazy {
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("win") -> System.getenv("LOCALAPPDATA")
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local").toString()

            os.contains("mac") -> Paths.get(
                System.getProperty("user.home"), "Library", "Application Support",
            ).toString()

            else -> System.getenv("XDG_DATA_HOME")
                ?: Paths.get(System.getProperty("user.home"), ".local", "share").toString()
        }
        Paths.get(base, "DividendStream").also { Files.createDirectories(it) }
    }

    /** PostgreSQL's data directory. Persisted, so holdings survive a restart. */
    val database: Path get() = root.resolve("db")

    /** Session tokens and the offline snapshot. */
    val state: Path get() = root.resolve("state").also { Files.createDirectories(it) }

    val logs: Path get() = root.resolve("logs").also { Files.createDirectories(it) }
}
