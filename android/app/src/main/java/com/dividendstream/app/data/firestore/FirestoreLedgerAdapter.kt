package com.dividendstream.app.data.firestore

import com.dividendstream.app.core.AppError
import com.dividendstream.app.data.repository.LedgerResult
import com.dividendstream.app.data.repository.LedgerSource
import com.dividendstream.app.domain.StoredFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Firestore ledger, in the shape the rest of the app already asks questions in.
 *
 * Thin on purpose. Everything of substance happens either side of it -- Firestore holds the
 * records, [com.dividendstream.app.domain.LedgerCalculator] works the figures out -- and this
 * only translates between the two vocabularies so that neither had to be bent to fit the
 * other.
 */
class FirestoreLedgerAdapter(
    private val repository: FirestoreLedgerRepository,
    /**
     * The one-off move off the old server, or null once there is nothing to move.
     *
     * Run on the first read that finds this ledger empty, rather than behind a button. The
     * person has already asked for their ledger to be here; being asked a second time, by a
     * card, on a screen that is empty precisely because the answer has not been acted on yet,
     * would be the app making its own migration somebody else's chore.
     *
     * It cannot do harm unasked: it refuses outright if anything is already stored, and it
     * writes under the ids the records already had, so a second run lands on the same
     * documents rather than beside them.
     */
    private val migration: LedgerMigration? = null,
) : LedgerSource {

    private val migrated = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun ledger(period: String, browsing: String?): Flow<LedgerResult> =
        repository.ledger(period, browsing?.let { runCatching { YearMonth.parse(it) }.getOrNull() })
            .onEach { ledger ->
                val empty = ledger.flows.isEmpty() && ledger.entries.isEmpty() && ledger.funds.isEmpty()
                val move = migration
                if (empty && move != null && migrated.compareAndSet(false, true)) {
                    // Failure is deliberately not fatal, and deliberately not final. The old
                    // server sleeps, so the very first attempt is the one most likely to time
                    // out -- and giving up until the app is restarted would leave somebody
                    // staring at an empty ledger with no way to ask again.
                    val outcome = runCatching { move.run() }
                    val moved = outcome.getOrNull() is LedgerMigration.Outcome.Moved ||
                        outcome.getOrNull() is LedgerMigration.Outcome.AlreadyThere
                    if (!moved) migrated.set(false)
                }
            }
            // Never stale. There is no newer copy being waited on that this one stands in for:
            // it *is* the copy, and anything newer arrives as another emission rather than as
            // a reason to apologise for this one.
            .map { LedgerResult(ledger = it, isStale = false) }
            // Turned into a result rather than left to escape as a crash: an account that is
            // not connected yet is a thing to tell somebody, not a thing to fall over on.
            .catch { failure ->
                emit(
                    LedgerResult(
                        ledger = null,
                        error = AppError(
                            code = "NOT_CONNECTED",
                            message = failure.message ?: "Sign in again to connect your ledger.",
                        ),
                    ),
                )
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
        val begins = startsOn ?: LocalDate.now()
        val updated = StoredFlow(
            id = id, name = name, direction = direction, amount = amount, period = period,
            category = category, arrivesOn = arrivesOn, arrivesMonth = arrivesMonth,
            startsOn = begins, endsOn = endsOn,
        )

        // A raise, rather than a correction: close the old one the evening before and carry the
        // new figures forward on a second, so every finished month still answers with what was
        // true in it. The existing flow has to be read first because the split needs the dates
        // it already had, not the ones the form is holding.
        val existing = effectiveFrom?.let { from ->
            repository.ledger("MONTH", null).first().flows
                .firstOrNull { it.id == id }
                ?.takeIf { it.startsOn.isBefore(from) && (it.endsOn == null || !it.endsOn.isBefore(from)) }
        }

        if (existing != null && effectiveFrom != null) {
            repository.splitFlow(
                existing = StoredFlow(
                    id = existing.id, name = existing.name, direction = existing.direction,
                    amount = existing.amount, period = existing.period, category = existing.category,
                    currency = existing.currency, arrivesOn = existing.arrivesOn,
                    arrivesMonth = existing.arrivesMonth, startsOn = existing.startsOn,
                    endsOn = existing.endsOn,
                ),
                successorId = java.util.UUID.randomUUID().toString(),
                effectiveFrom = effectiveFrom,
                updated = updated,
            )
            return
        }

        repository.saveFlow(
            id, name, direction, amount, period, category, arrivesOn, arrivesMonth, begins, endsOn,
        )
    }

    override suspend fun deleteFlow(id: String) = repository.deleteFlow(id)

    override suspend fun saveEntry(
        id: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        category: String?,
        note: String?,
    ) = repository.saveEntry(id, direction, amount, occurredOn, category, note)

    override suspend fun deleteEntry(id: String) = repository.deleteEntry(id)

    override suspend fun saveFund(id: String, name: String, percent: BigDecimal, icon: String?) =
        repository.saveFund(id, name, percent, icon)

    override suspend fun deleteFund(id: String) = repository.deleteFund(id)

    override suspend fun saveFundMovement(
        id: String,
        fundId: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        note: String?,
    ) = repository.saveFundMovement(id, fundId, direction, amount, occurredOn, note)

    override suspend fun deleteFundMovement(id: String) = repository.deleteFundMovement(id)
}
