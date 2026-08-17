package com.dividendstream.api.config

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Records the moment the application became ready to serve.
 *
 * Taking the time in a bean's constructor instead would read it partway through context refresh:
 * on a slow instance this application's own logs put bean creation a full fourteen seconds ahead
 * of "Started", which is a large error to build a diagnostic on. [ApplicationReadyEvent] fires
 * once everything is up, which is the moment anyone asking actually means.
 *
 * Null until the event fires. Tomcat accepts connections slightly before it does, so there is a
 * brief window where the honest answer is "not yet".
 */
@Component
class ReadinessClock(private val clock: Clock) : ApplicationListener<ApplicationReadyEvent> {

    @Volatile
    private var readyAt: Instant? = null

    override fun onApplicationEvent(event: ApplicationReadyEvent) = markReady()

    /**
     * Separated from the listener so a test can reach it without building a Spring event, which
     * needs a live application and context to construct.
     */
    fun markReady() {
        readyAt = Instant.now(clock)
    }

    fun readyAt(): Instant? = readyAt

    /** Seconds since the application became ready, or null if it has not yet. */
    fun uptimeSeconds(): Long? =
        readyAt?.let { java.time.Duration.between(it, Instant.now(clock)).seconds }
}
