package com.dividendstream.app.data.firestore

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.repository.LedgerRepository
import com.dividendstream.app.domain.StoredEntry
import com.dividendstream.app.domain.StoredFlow
import com.dividendstream.app.domain.StoredFund
import com.dividendstream.app.domain.StoredMovement
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Copies a ledger out of the old server and into Firestore, once.
 *
 * Written as a one-off on purpose. It reads through the API with the session the person is
 * already signed in with -- no database credentials anywhere near it -- and writes with the
 * ids the records already had, so running it twice lands on the same documents rather than
 * beside them. A half-finished run can simply be run again.
 *
 * It refuses to touch a Firestore ledger that already has something in it. Merging two ledgers
 * is a judgement about which of two figures is right, and nothing here is entitled to make it.
 */
class LedgerMigration(
    private val api: LedgerRepository,
    private val firestore: FirebaseFirestore,
    private val session: FirebaseSessionRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    sealed interface Outcome {
        data class Moved(val flows: Int, val entries: Int, val funds: Int, val movements: Int) : Outcome
        data object AlreadyThere : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun run(): Outcome {
        val account = session.current ?: return Outcome.Failed("Sign in again first.")
        val source = FirestoreLedgerSource(firestore, account.uid)

        // Judged one collection at a time rather than all together.
        //
        // A single verdict meant a run that got the records across but not the funds could
        // never be repeated: the ledger was no longer empty, so the second attempt refused,
        // and the funds stayed missing with no way to ask again. Each kind is now brought
        // over only if there is none of it here, which leaves a half-finished move able to
        // finish itself while still never pouring an old ledger onto a used one.
        val existing = source.stream(account.baseCurrency).first()
        if (existing.flows.isNotEmpty() && existing.entries.isNotEmpty() &&
            existing.funds.isNotEmpty()
        ) {
            return Outcome.AlreadyThere
        }
        val needsFlows = existing.flows.isEmpty()
        val needsFunds = existing.funds.isEmpty()
        val needsEntries = existing.entries.isEmpty()

        val current = when (val result = api.ledger("MONTH", null)) {
            is AppResult.Success -> result.data.value
            is AppResult.Failure -> return Outcome.Failed(result.error.message)
        }

        // --- what repeats, and where it goes ---------------------------------
        //
        // Both are answered whole by any single call: a flow and a fund are things that exist,
        // not things that happened in a window.
        if (needsFlows) current.flows.forEach { flow ->
            source.put(
                StoredFlow(
                    id = flow.id,
                    name = flow.name,
                    direction = flow.direction,
                    amount = flow.amount,
                    period = flow.period,
                    category = flow.category,
                    currency = flow.currency,
                    arrivesOn = flow.arrivesOn,
                    arrivesMonth = flow.arrivesMonth,
                    startsOn = flow.startsOn,
                    endsOn = flow.endsOn,
                ),
            )
        }

        var movements = 0
        if (needsFunds) current.funds.forEach { fund ->
            // The API has no created-at for a fund, and settlement walks back from it. The
            // earliest month it has already banked is the honest answer: earlier than that and
            // months it settled long ago would be worked out again from today's figures.
            val earliest = fund.movements.minOfOrNull { it.occurredOn }
            source.put(
                StoredFund(
                    id = fund.id,
                    name = fund.name,
                    percent = fund.percent,
                    icon = fund.icon,
                    position = fund.position,
                    createdAt = (earliest ?: LocalDate.now(zone)).atStartOfDay(zone).toInstant(),
                ),
            )
            fund.movements.forEach { movement ->
                source.put(
                    StoredMovement(
                        id = movement.id,
                        fundId = fund.id,
                        occurredOn = movement.occurredOn,
                        direction = movement.direction,
                        amount = movement.amount,
                        note = movement.note,
                        source = movement.source,
                        settledMonth = movement.settledMonth,
                    ),
                )
                movements++
            }
        }

        // --- what happened once ----------------------------------------------
        //
        // These come a month at a time, because that is how the API answers: one call carries
        // the window it was asked for and nothing else. The months list says which ones hold
        // anything, so no month is fetched to be told it is empty.
        val entries = mutableMapOf<String, StoredEntry>()
        if (needsEntries) current.entries.forEach { entries[it.id] = it.toStored() }

        val months = if (needsEntries) current.months.filter { it.entryCount > 0 }.map { it.month } else emptyList()
        for (month in months) {
            if (month == YearMonth.now(zone).toString()) continue
            when (val result = api.ledger("MONTH", month)) {
                is AppResult.Success -> result.data.value.entries.forEach { entries[it.id] = it.toStored() }
                // One unreachable month is not a reason to abandon the rest. Running it again
                // fills the gap, because every write lands on the id it already had.
                is AppResult.Failure -> Unit
            }
        }
        entries.values.forEach { source.put(it) }

        return Outcome.Moved(
            flows = current.flows.size,
            entries = entries.size,
            funds = current.funds.size,
            movements = movements,
        )
    }

    private fun com.dividendstream.app.data.remote.LedgerEntryDto.toStored() =
        StoredEntry(id, occurredOn, direction, amount, category, note)
}
