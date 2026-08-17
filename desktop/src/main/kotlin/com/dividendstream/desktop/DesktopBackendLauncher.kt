package com.dividendstream.desktop

import com.dividendstream.api.DividendStreamApplication
import com.dividendstream.app.AppPaths
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import javax.sql.DataSource

/**
 * A PostgreSQL server, run in-process against a persistent data directory.
 *
 * This is the same mechanism the test suite uses, pointed at the user's profile instead of a
 * temporary directory. It is a real PostgreSQL, so the Flyway migrations and every `NUMERIC`
 * money column behave exactly as they do on a server — which an embedded substitute in
 * "PostgreSQL compatibility mode" would not guarantee.
 */
@Configuration(proxyBeanMethods = false)
class DesktopDataSourceConfig {

    @Bean(destroyMethod = "close")
    fun embeddedPostgres(): EmbeddedPostgres {
        val dataDirectory = AppPaths.database.toFile()
        clearStaleLock(dataDirectory)
        return EmbeddedPostgres.builder()
            .setDataDirectory(dataDirectory)
            // Without this the directory is wiped on every start, and the user would lose
            // their holdings each time they closed the app.
            .setCleanDataDirectory(false)
            // Loopback only. The default binds every interface, which makes Windows raise a
            // firewall prompt on first launch and, worse, offers this user's database to the
            // rest of whatever network they are on. Nothing outside this process ever needs
            // to reach it.
            .setServerConfig("listen_addresses", "127.0.0.1")
            .start()
    }

    /** Defining this suppresses Boot's DataSource auto-configuration. */
    @Bean
    @Primary
    fun dataSource(embeddedPostgres: EmbeddedPostgres): DataSource = embeddedPostgres.postgresDatabase

    /**
     * Removes a `postmaster.pid` left behind by a machine that lost power or a process that
     * was killed rather than closed.
     *
     * PostgreSQL refuses to start while that file names a live process, which is correct.
     * But after a crash the file names a process that no longer exists, and the app would
     * then refuse to start for good with no way for the user to fix it. Only a lock whose
     * process is genuinely gone is cleared; a running server is left well alone, because
     * deleting its lock file would risk two servers writing to one data directory.
     */
    private fun clearStaleLock(dataDirectory: File) {
        val lock = File(dataDirectory, "postmaster.pid")
        if (!lock.isFile) return

        val pid = runCatching { lock.readLines().firstOrNull()?.trim()?.toLong() }.getOrNull()
        if (pid == null) {
            lock.delete()
            return
        }

        val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        if (!alive) lock.delete()
    }
}

object DesktopBackendLauncher {

    @Volatile
    private var context: ConfigurableApplicationContext? = null

    fun run() {
        context = SpringApplicationBuilder(
            DividendStreamApplication::class.java,
            DesktopDataSourceConfig::class.java,
        )
            // Nothing to show a banner to; the window is the user interface.
            .logStartupInfo(false)
            .headless(false)
            .run()
    }

    /**
     * Closes the Spring context, which runs the embedded server's `close` and shuts the
     * PostgreSQL cluster down.
     *
     * Skipping this leaves a running postgres behind holding `postmaster.pid`, and the next
     * launch then fails to start against its own data directory. Killing the JVM outright
     * has exactly that effect, so closing here is the difference between a clean quit and a
     * broken next launch.
     */
    fun stop() {
        runCatching { context?.close() }
        context = null
    }
}

/**
 * The JWT signing secret for this installation.
 *
 * Generated on first launch and kept in the user's profile. Baking a secret into a
 * downloadable installer would mean every copy of the app shared one key, so a token minted
 * on any machine would verify on every other.
 */
object InstallSecret {

    fun get(): String {
        val file = AppPaths.state.resolve("jwt.key")
        val existing = runCatching { Files.readString(file).trim() }.getOrNull()
        return if (existing != null && existing.length >= MIN_LENGTH) existing else generate(file)
    }

    private const val MIN_LENGTH = 32

    private fun generate(file: java.nio.file.Path): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        Files.writeString(file, secret)
        return secret
    }
}
