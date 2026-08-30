package com.dividendstream.app.data.repository

import com.dividendstream.app.data.remote.LedgerDto
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Where the ledger screen gets its figures, and where its changes go.
 *
 * Two things implement this and they work quite differently. On a phone it is Firestore: the
 * data is on the device, a listener answers immediately and again whenever anything changes,
 * and a write is applied locally before it is sent anywhere. On the desktop it is still the
 * HTTP API, because the Firebase SDK this app uses is Android-only -- so that one asks a
 * server, waits, and emits once.
 *
 * The seam exists so there is one view model rather than two. Everything above this line reads
 * the same [LedgerDto] either way and does not get to know which it is talking to.
 */
interface LedgerSource {

    /**
     * The ledger, and every later version of it.
     *
     * A flow rather than a single answer because one of the two implementations genuinely has
     * more to say later: a month ends, the desktop writes something, a queued change lands.
     * The other emits once and completes, which the caller cannot tell apart and does not
     * need to.
     */
    fun ledger(period: String, browsing: String?): Flow<LedgerResult>

    suspend fun saveFlow(
        id: String,
        name: String,
        direction: String,
        amount: BigDecimal,
        period: String,
        category: String?,
        arrivesOn: Int?,
        arrivesMonth: Int?,
        startsOn: LocalDate?,
        endsOn: LocalDate?,
        effectiveFrom: LocalDate?,
    )

    suspend fun deleteFlow(id: String)

    suspend fun saveEntry(
        id: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        category: String?,
        note: String?,
    )

    suspend fun deleteEntry(id: String)

    suspend fun saveFund(id: String, name: String, percent: BigDecimal, icon: String?)

    suspend fun deleteFund(id: String)

    suspend fun saveFundMovement(
        id: String,
        fundId: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        note: String?,
    )

    suspend fun deleteFundMovement(id: String)
}

/**
 * One version of the ledger, and how much to trust it.
 *
 * [isStale] and [error] are what the screen paints its banner from. Firestore answers from the
 * device and is never stale in the sense that matters here -- there is no newer copy being
 * waited on that this one is standing in for -- while the API's saved copy very much is.
 */
data class LedgerResult(
    val ledger: LedgerDto?,
    val isStale: Boolean = false,
    val cachedAt: java.time.Instant? = null,
    val error: com.dividendstream.app.core.AppError? = null,
)
