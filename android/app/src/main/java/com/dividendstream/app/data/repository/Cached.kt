package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppError
import java.time.Instant

/**
 * Data plus its provenance.
 *
 * When the network fails but a saved copy exists, the app shows the saved copy rather than
 * an empty screen -- but it says so. [staleError] carries the reason so the UI can explain
 * why the figures may be behind.
 */
data class Cached<T>(
    val value: T,
    val isStale: Boolean = false,
    val cachedAt: Instant? = null,
    val staleError: AppError? = null,
)
