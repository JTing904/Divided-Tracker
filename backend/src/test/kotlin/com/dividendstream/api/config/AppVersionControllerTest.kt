package com.dividendstream.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AppVersionControllerTest {

    private val ready = Instant.parse("2026-08-17T08:53:14Z")

    /** Hands out each instant in turn, so successive readings can differ. */
    private class SteppingClock(instants: List<Instant>) : Clock() {
        private val readings = instants.iterator()
        private var last = instants.first()
        override fun instant(): Instant {
            if (readings.hasNext()) last = readings.next()
            return last
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private fun readinessClock(advance: Duration = Duration.ofSeconds(22)): ReadinessClock =
        ReadinessClock(SteppingClock(listOf(ready, ready.plus(advance)))).also { it.markReady() }

    private fun controller(
        properties: ReleaseProperties = ReleaseProperties(),
        readiness: ReadinessClock = readinessClock(),
    ) = AppVersionController(properties, readiness, "dividend-stream")

    @Test
    @DisplayName("unset release settings report null, not a claim that the client is current")
    fun `blank configuration becomes null`() {
        val response = controller().version()

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
    @DisplayName("whitespace is treated as unset, so a stray space cannot become a version")
    fun `whitespace only configuration becomes null`() {
        val response = controller(
            ReleaseProperties(latestClient = "   ", minimumClient = "\t", commit = " "),
        ).version()

        assertThat(response.latestClient).isNull()
        assertThat(response.minimumClient).isNull()
        assertThat(response.commit).isNull()
    }

    @Test
    @DisplayName("uptime runs from readiness, and both are absent until the app is ready")
    fun `uptime is measured from application readiness`() {
        val notYetReady = ReadinessClock(SteppingClock(listOf(ready)))
        val before = controller(readiness = notYetReady).version()

        // Tomcat accepts connections slightly before the ready event, and "not yet" is a more
        // useful answer there than a number taken partway through startup.
        assertThat(before.readyAt).isNull()
        assertThat(before.uptimeSeconds).isNull()

        val after = controller(readiness = readinessClock(advance = Duration.ofSeconds(22))).version()
        assertThat(after.readyAt).isEqualTo(ready)
        assertThat(after.uptimeSeconds).isEqualTo(22)
    }
}
