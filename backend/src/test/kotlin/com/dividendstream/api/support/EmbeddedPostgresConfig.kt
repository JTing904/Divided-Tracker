package com.dividendstream.api.support

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import javax.sql.DataSource

/**
 * Runs a real PostgreSQL server in-process for the duration of the test context.
 *
 * Tests execute against the same engine and dialect as production, so the Flyway migrations
 * and every `NUMERIC` precision guarantee are genuinely exercised -- which an in-memory
 * substitute in "PostgreSQL compatibility mode" would not do. No Docker required.
 */
@TestConfiguration(proxyBeanMethods = false)
class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    fun embeddedPostgres(): EmbeddedPostgres = EmbeddedPostgres.builder().start()

    /** Defining this suppresses Boot's DataSource auto-configuration. */
    @Bean
    @Primary
    fun dataSource(embeddedPostgres: EmbeddedPostgres): DataSource = embeddedPostgres.postgresDatabase
}
