package com.dividendstream.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AppVersionControllerTest {

    private val boot = Instant.parse("2026-08-17T08:00:00Z")

    /** Reports [boot] once, then [boot] plus [advance] on every later reading. */
    private class SteppingClock(private val readings: Iterator<Instant>) : Clock() {
        override fun instant(): Instant = readings.next()
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private fun controller(
        properties: ReleaseProperties,
        advance: Duration = Duration.ofMinutes(3),
    ) = AppVersionController(
        releaseProperties = properties,
        clock = SteppingClock(listOf(boot, boot.plus(advance)).iterator()),
        serviceName = "dividend-stream",
    )

    @Test
    @DisplayName("unset release settings report null, not a claim that the client is current")
    fun `blank configuration becomes null`() {
        val response = controller(ReleaseProperties()).version()

        assertThat(response.latestClient).isNull()
        assertThat(response.minimumClient).isNull()
        assertThat(response.commit).isNull()
        assertThat(response.service).isEqualTo("dividend-stream")
    }

    @Test
    fun `configured values are reported as given`() {
        val response = controller(
            ReleaseProperties(latestClient = "1.0.1", minimumClient = "1.0.0", commit = "6396033"),
        ).version()

        assertThat(response.latestClient).isEqualTo("1.0.1")
        assertThat(response.minimumClient).isEqualTo("1.0.0")
        assertThat(response.commit).isEqualTo("6396033")
    }

    @Test
    @DisplayName("startedAt is when the process booted, and uptime grows from it")
    fun `uptime is measured from construction`() {
        // The point of the field: a caller seeing a few seconds of uptime knows it has just
        // cold-started, which is the difference between "slow" and "was asleep".
        val response = controller(ReleaseProperties(), advance = Duration.ofMinutes(3)).version()

        assertThat(response.startedAt).isEqualTo(boot)
        assertThat(response.uptimeSeconds).isEqualTo(180)
    }

    @Test
    @DisplayName("whitespace is treated as unset, so a stray space cannot become a version")
    fun `whitespace only configuration becomes null`() {
        val response = controller(
            ReleaseProperties(latestClient = "   ", minimumClient = "\t", commit = " "),
        ).version()

        assertThat(response.latestClient).isNull()
        assertThat(response.minimumClient).isNull()
        assertThat(response.commit).isNull()
    }
}
