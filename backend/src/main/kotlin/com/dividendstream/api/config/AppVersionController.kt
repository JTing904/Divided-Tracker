package com.dividendstream.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Says which build is running and which client release is current.
 *
 * Unauthenticated on purpose. A client has to be able to ask "am I too old to be trusted with
 * this?" before it has a session -- and being locked out is exactly the situation where it
 * most needs to be able to explain itself. It reads no database, so it also answers while the
 * database is unreachable, which makes it the one endpoint worth pointing a monitor at.
 *
 * Nothing here is a secret: the repository is public, and an attacker learns nothing from a
 * commit hash that they could not read on GitHub.
 */
@RestController
@RequestMapping("/api/app")
class AppVersionController(
    private val releaseProperties: ReleaseProperties,
    private val clock: Clock,
    @Value("\${spring.application.name:dividend-stream}") private val serviceName: String,
) {

    /** Fixed at construction, so it reports when this process booted rather than "now". */
    private val startedAt: Instant = Instant.now(clock)

    @GetMapping("/version")
    fun version(): AppVersionResponse = AppVersionResponse(
        service = serviceName,
        commit = releaseProperties.commit.ifBlank { null },
        latestClient = releaseProperties.latestClient.ifBlank { null },
        minimumClient = releaseProperties.minimumClient.ifBlank { null },
        startedAt = startedAt,
        uptimeSeconds = Duration.between(startedAt, Instant.now(clock)).seconds,
    )
}

/**
 * Nulls are deliberate and meaningful: they say "not configured", which is different from a
 * blank string and very different from an assurance that the client is current.
 */
data class AppVersionResponse(
    val service: String,
    val commit: String?,
    val latestClient: String?,
    val minimumClient: String?,
    val startedAt: Instant,
    /** How long this process has been up. A small value means it has just cold-started. */
    val uptimeSeconds: Long,
)
