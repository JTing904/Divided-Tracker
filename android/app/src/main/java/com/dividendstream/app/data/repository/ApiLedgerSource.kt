package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The ledger as it was: fetched over HTTP, through the queue, from a server that may be asleep.
 *
 * Still what the desktop uses, because the Firebase SDK is Android-only. It is deliberately
 * unchanged behaviour rather than a stepping stone -- a desktop on this path keeps the saved
 * copy, the stale banner and the offline queue exactly as they were, so nothing regresses for
 * a client that has not moved yet.
 */
class ApiLedgerSource(
    private val repository: LedgerRepository,
    private val queue: LedgerQueue,
) : LedgerSource {

    override fun ledger(period: String, browsing: String?): Flow<LedgerResult> = flow {
        // The saved copy first, before the request goes out rather than after it fails. The
        // server sleeps between uses and these figures are derived from timestamps, so a saved
        // ledger is still counting, and still correct, while it wakes.
        repository.cachedLedger()?.let { emit(LedgerResult(it.value, isStale = true, cachedAt = it.cachedAt)) }

        when (val result = repository.ledger(period, browsing)) {
            is AppResult.Success -> emit(
                LedgerResult(
                    ledger = result.data.value,
                    isStale = result.data.isStale,
                    cachedAt = result.data.cachedAt,
                    error = result.data.staleError,
                ),
            )

            is AppResult.Failure -> emit(LedgerResult(ledger = null, error = result.error))
        }
    }

    override suspend fun saveFlow(
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
    ) {
        queue.submit(
            com.dividendstream.app.domain.PendingLedgerWrite.Flow(
                key = id,
                name = name,
                direction = direction,
                amount = amount,
                period = period,
                category = category,
                arrivesOn = arrivesOn,
                arrivesMonth = arrivesMonth,
                startsOn = startsOn,
                endsOn = endsOn,
                effectiveFrom = effectiveFrom,
                successorId = effectiveFrom?.let { java.util.UUID.randomUUID().toString() },
                queuedAt = java.time.Instant.now(),
            ),
        )
    }

    override suspend fun deleteFlow(id: String) =
        queue.submitDelete(com.dividendstream.app.domain.PendingLedgerWrite.Delete.Target.FLOW, id, "this")

    override suspend fun saveEntry(
        id: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        category: String?,
        note: String?,
    ) {
        queue.submit(
            com.dividendstream.app.domain.PendingLedgerWrite.Entry(
                key = id,
                direction = direction,
                amount = amount,
                occurredOn = occurredOn,
                category = category,
                note = note,
                queuedAt = java.time.Instant.now(),
            ),
        )
    }

    override suspend fun deleteEntry(id: String) =
        queue.submitDelete(com.dividendstream.app.domain.PendingLedgerWrite.Delete.Target.ENTRY, id, "this record")

    override suspend fun saveFund(id: String, name: String, percent: BigDecimal, icon: String?) {
        queue.submit(
            com.dividendstream.app.domain.PendingLedgerWrite.Fund(
                key = id,
                name = name,
                percent = percent,
                icon = icon,
                queuedAt = java.time.Instant.now(),
            ),
        )
    }

    override suspend fun deleteFund(id: String) =
        queue.submitDelete(com.dividendstream.app.domain.PendingLedgerWrite.Delete.Target.FUND, id, "this fund")

    override suspend fun saveFundMovement(
        id: String,
        fundId: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        note: String?,
    ) {
        queue.submit(
            com.dividendstream.app.domain.PendingLedgerWrite.Movement(
                key = id,
                fundId = fundId,
                direction = direction,
                amount = amount,
                occurredOn = occurredOn,
                note = note,
                queuedAt = java.time.Instant.now(),
            ),
        )
    }

    override suspend fun deleteFundMovement(id: String) =
        queue.submitDelete(com.dividendstream.app.domain.PendingLedgerWrite.Delete.Target.MOVEMENT, id, "this entry")
}
