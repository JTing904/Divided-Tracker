package com.dividendstream.app.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Whether the server is answering, shared by every screen.
 *
 * Free hosting stops the container after a spell of no traffic, and waking one takes long
 * enough that a spinner stops reading as "loading" and starts reading as "broken". Cached
 * figures cover the screens that only display; they cannot cover searching for a stock or
 * recording a purchase, which need a server that is actually running. For those, the honest
 * thing is to say what is happening and roughly how long it takes.
 *
 * Fed by [ColdStartInterceptor], so it learns from real traffic rather than from a separate
 * poll of its own: any answer at all means awake, and a timeout means a wake is under way.
 */
class ServerAvailability {

    sealed interface Status {
        /** Nothing has been attempted yet this launch. */
        data object Unknown : Status

        /** A request is outstanding and has already timed out once. */
        data class Waking(val since: Instant) : Status

        data object Awake : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Unknown)
    val status: StateFlow<Status> = _status.asStateFlow()

    fun reportAwake() {
        _status.value = Status.Awake
    }

    /**
     * Keeps the original [Status.Waking.since], so the elapsed time shown to the user counts
     * from when the wait actually began rather than restarting with every retry.
     */
    fun reportWaking(now: Instant) {
        if (_status.value !is Status.Waking) _status.value = Status.Waking(now)
    }

    /**
     * Roughly how long a cold start takes, measured against this project's own deployment: a
     * container to start, a JVM and Spring context to come up, and a servlet to initialise on
     * the first request through it.
     */
    companion object {
        const val TYPICAL_WAKE_SECONDS = 110L
    }
}
