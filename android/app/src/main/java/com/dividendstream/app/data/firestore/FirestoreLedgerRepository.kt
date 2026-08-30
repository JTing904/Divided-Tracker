package com.dividendstream.app.data.firestore

import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.domain.LedgerCalculator
import com.dividendstream.app.domain.StoredEntry
import com.dividendstream.app.domain.StoredFlow
import com.dividendstream.app.domain.StoredFund
import com.dividendstream.app.domain.StoredLedger
import com.dividendstream.app.domain.StoredMovement
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The ledger screen's data, assembled on the device.
 *
 * Two halves that used to be one HTTP call: Firestore says what is stored, and
 * [LedgerCalculator] works out everything shown from it. The result is the same [LedgerDto]
 * the API returned, so nothing above this line knows the difference.
 *
 * It hands back a [Flow] rather than a single answer, because that is the difference being
 * bought. A request had to complete before a figure could be shown, and on a sleeping free
 * instance that was a hundred seconds of nothing. A listener answers immediately from the copy
 * on the device, and again -- on its own -- when anything changes, whether the change came
 * from this phone, the desktop, or a month finally ending.
 */
class FirestoreLedgerRepository(
    private val firestore: FirebaseFirestore,
    private val session: FirebaseSessionRepository,
    private val clock: ServerClock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private fun source(): FirestoreLedgerSource? =
        session.current?.let { FirestoreLedgerSource(firestore, it.uid) }

    /**
     * The ledger, recomputed whenever what is stored changes.
     *
     * A month that has finished is banked as a side effect of being read, which is where it
     * has to happen: there is no server left to run a schedule, and the alternative -- working
     * the same past month out again on every screen -- is what let editing a salary today
     * rewrite what August put aside.
     */
    fun ledger(period: String, browsing: YearMonth?): Flow<LedgerDto> {
        val source = source() ?: return flowOf(empty())
        val currency = session.current?.baseCurrency ?: "MYR"
        return source.stream(currency).map { stored ->
            val result = LedgerCalculator.calculate(stored, period, browsing, clock.now(), zone)
            if (result.settlements.isNotEmpty()) {
                // Fire and forget on purpose. The figures already account for it, so a write
                // that has not landed yet changes nothing on screen -- and Firestore will send
                // it whenever it can regardless of what happens next.
                runCatching { source.settle(result.settlements) }
            }
            result.ledger
        }
    }

    // --- writing ---------------------------------------------------------------
    //
    // No queue and no result to wait for. Firestore applies a write to the copy on the device
    // first, which means the listener above has already emitted the new figures by the time
    // these return, and sends it to the server whenever there is a server to send it to.

    suspend fun saveFlow(
        id: String,
        name: String,
        direction: String,
        amount: BigDecimal,
        period: String,
        category: String?,
        arrivesOn: Int?,
        arrivesMonth: Int?,
        startsOn: LocalDate,
        endsOn: LocalDate?,
    ) {
        source()?.put(
            StoredFlow(
                id = id, name = name, direction = direction, amount = amount, period = period,
                category = category, arrivesOn = arrivesOn, arrivesMonth = arrivesMonth,
                startsOn = startsOn, endsOn = endsOn,
            ),
        )
    }

    /**
     * Ends one flow the day before [effectiveFrom] and starts another on it.
     *
     * The server did this in one transaction; here it is two writes that Firestore applies
     * locally together and sends in order. A raise is not a correction, and editing the amount
     * in place would recompute every finished month at the new figure.
     */
    suspend fun splitFlow(existing: StoredFlow, successorId: String, effectiveFrom: LocalDate, updated: StoredFlow) {
        val source = source() ?: return
        source.put(existing.copy(endsOn = effectiveFrom.minusDays(1)))
        source.put(updated.copy(id = successorId, startsOn = effectiveFrom, endsOn = existing.endsOn))
    }

    suspend fun deleteFlow(id: String) {
        source()?.deleteFlow(id)
    }

    suspend fun saveEntry(
        id: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        category: String?,
        note: String?,
    ) {
        source()?.put(StoredEntry(id, occurredOn, direction, amount, category, note))
    }

    suspend fun deleteEntry(id: String) {
        source()?.deleteEntry(id)
    }

    suspend fun saveFund(id: String, name: String, percent: BigDecimal, icon: String?, position: Int = 0) {
        val source = source() ?: return
        // createdAt decides which months settlement walks back through, so it is written once
        // and never touched again -- merge leaves the original in place on a later edit.
        source.put(StoredFund(id, name, percent, icon, position, clock.now()))
    }

    suspend fun deleteFund(id: String) {
        source()?.deleteFund(id)
    }

    suspend fun saveFundMovement(
        id: String,
        fundId: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate,
        note: String?,
    ) {
        source()?.put(StoredMovement(id, fundId, occurredOn, direction, amount, note))
    }

    suspend fun deleteFundMovement(id: String) {
        source()?.deleteMovement(id)
    }

    /** What a signed-out screen shows: nothing, rather than a stale somebody else's. */
    private fun empty(): LedgerDto =
        LedgerCalculator.calculate(StoredLedger(), "MONTH", null, clock.now(), zone).ledger
}
