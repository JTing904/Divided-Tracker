package com.dividendstream.api

import com.dividendstream.api.support.EmbeddedPostgresConfig
import org.springframework.boot.fromApplication
import org.springframework.boot.with

/**
 * Runs the real application against an in-process PostgreSQL server: `./gradlew bootTestRun`.
 *
 * This lives in the test source set on purpose. The production classpath stays free of test
 * database machinery, and `./gradlew bootRun` still expects a real DATABASE_URL.
 */
fun main(args: Array<String>) {
    System.setProperty("spring.profiles.active", "local")
    fromApplication<DividendStreamApplication>()
        .with(EmbeddedPostgresConfig::class)
        .run(*args)
}
