package com.dividendstream.app.core

import java.time.Instant

/**
 * The clock the live counter runs on.
 *
 * Every accrued value is a function of "now", so a device whose system time is wrong would
 * otherwise show a wrong -- possibly negative or complete -- dividend. Each API response
 * carries the server's time; the difference is kept here and applied to every reading.
 *
 * The offset is persisted with the cached snapshot so the counter is still correct on a
 * cold, offline start.
 */
class ServerClock {

    @Volatile
    private var offsetMillis: Long = 0L

    val offset: Long get() = offsetMillis

    /** Called on every successful response that carries a server timestamp. */
    fun syncTo(serverTime: Instant) {
        offsetMillis = serverTime.toEpochMilli() - System.currentTimeMillis()
    }

    fun restore(offsetMillis: Long) {
        this.offsetMillis = offsetMillis
    }

    fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis)
}
