package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.local.PendingLedgerStore
import com.dividendstream.app.data.remote.ServerAvailability
import com.dividendstream.app.domain.PendingLedgerWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Takes a ledger change off the person's hands and gets it to the server eventually.
 *
 * The same bargain the purchase queue makes, for the same reason and with more at stake. The
 * server sleeps between uses and takes a minute or two to wake; a purchase happens a few times
 * a month and writing down a lunch happens several times a day, standing at a counter. Making
 * somebody watch a spinner for that is how they stop keeping the ledger at all.
 *
 * Everything goes through here -- records, funds, movements, recurring flows, and deletions --
 * rather than only the parts that felt slow. One path is easier to trust than two, and picking
 * between them would mean guessing whether the server is awake, which only the network knows
 * and only after asking.
 *
 * Retrying is safe because every ledger write is keyed by an id the client chooses: sending the
 * same change twice records it once. That was true from the first ledger commit, and it is what
 * makes this class possible rather than dangerous.
 */
class LedgerQueue(
    private val store: PendingLedgerStore,
    private val ledgerRepository: LedgerRepository,
    private val availability: ServerAvailability,
    private val scope: CoroutineScope,
) {

    val pending: Flow<List<PendingLedgerWrite>> = store.pending

    /** One drain at a time; two would present the same keys concurrently for no benefit. */
    private val draining = Mutex()

    /** Called after a change lands, so the screen can pick up what the server now says. */
    var onSettled: (() -> Unit)? = null

    init {
        drain()
        scope.launch {
            availability.status
                .filterIsInstance<ServerAvailability.Status.Awake>()
                .collect { revive() }
        }
    }

    /**
     * Forgets every refusal and tries again, because the server has just woken.
     *
     * A change is only ever set aside because sending it failed in a way that looked
     * permanent, and the commonest reason for that judgement to be wrong is the state that
     * has just changed. Something the server genuinely refuses is simply set aside again, one
     * request later; this fires once per waking, not once per response, because the status is
     * a StateFlow and only emits when it actually changes.
     */
    private suspend fun revive() {
        store.all().filter { it.isBlocked }.forEach { store.replace(it.unblocked()) }
        drain()
    }

    /**
     * Records the change and starts trying. Returns as soon as it is safely on the device,
     * which is the point: the person is free to put their phone away.
     */
    suspend fun submit(write: PendingLedgerWrite) {
        store.add(write)
        drain()
    }

    /**
     * Queues a deletion, unless the thing being deleted is itself still queued.
     *
     * Something the server has never heard of cannot be deleted from it -- the request would
     * be answered with "not found" and the queue would stop on a failure that is not one. So
     * the two cancel each other out here and the change simply never happened.
     */
    suspend fun submitDelete(
        target: PendingLedgerWrite.Delete.Target,
        id: String,
        label: String,
    ) {
        if (store.cancel(id)) {
            onSettled?.invoke()
            return
        }
        submit(
            PendingLedgerWrite.Delete(
                key = "delete:$id",
                target = target,
                id = id,
                label = label,
                queuedAt = Instant.now(),
            ),
        )
    }

    /** Forgets a change the server refused outright. Only the person can decide to drop one. */
    suspend fun discard(key: String) {
        store.remove(key)
        onSettled?.invoke()
    }

    /** Clears the refusal so a blocked change is tried again, once its cause is fixed. */
    suspend fun retry(key: String) {
        store.all().firstOrNull { it.key == key }?.let { store.replace(it.unblocked()) }
        drain()
    }

    fun drain() {
        scope.launch {
            draining.withLock {
                var settledAny = false
                // Oldest first, and it stops at the first thing that will not go. A fund has to
                // exist before money can be moved into it, so sending later changes past a
                // stuck earlier one would fail in a way that looks like the server refusing
                // something it has simply never been told about.
                for (write in store.all()) {
                    if (write.isBlocked) continue
                    if (!send(write)) break
                    settledAny = true
                }
                if (settledAny) onSettled?.invoke()
            }
        }
    }

    /** True when the queue may carry on: the change went, or it is stuck and marked as such. */
    private suspend fun send(write: PendingLedgerWrite): Boolean {
        val result: AppResult<*> = when (write) {
            is PendingLedgerWrite.Flow -> ledgerRepository.saveCashFlow(
                id = write.key,
                name = write.name,
                direction = write.direction,
                amount = write.amount,
                period = write.period,
                category = write.category,
                arrivesOn = write.arrivesOn,
                arrivesMonth = write.arrivesMonth,
                startsOn = write.startsOn,
                endsOn = write.endsOn,
                effectiveFrom = write.effectiveFrom,
                successorId = write.successorId,
            )

            is PendingLedgerWrite.Entry -> ledgerRepository.saveEntry(
                id = write.key,
                direction = write.direction,
                amount = write.amount,
                occurredOn = write.occurredOn,
                category = write.category,
                note = write.note,
            )

            is PendingLedgerWrite.Fund -> ledgerRepository.saveFund(
                id = write.key,
                name = write.name,
                percent = write.percent,
                icon = write.icon,
            )

            is PendingLedgerWrite.Movement -> ledgerRepository.saveFundMovement(
                fundId = write.fundId,
                id = write.key,
                direction = write.direction,
                amount = write.amount,
                occurredOn = write.occurredOn,
                note = write.note,
            )

            is PendingLedgerWrite.Delete -> when (write.target) {
                PendingLedgerWrite.Delete.Target.FLOW -> ledgerRepository.deleteCashFlow(write.id)
                PendingLedgerWrite.Delete.Target.ENTRY -> ledgerRepository.deleteEntry(write.id)
                PendingLedgerWrite.Delete.Target.FUND -> ledgerRepository.deleteFund(write.id)
                PendingLedgerWrite.Delete.Target.MOVEMENT ->
                    ledgerRepository.deleteFundMovement(write.id)
            }
        }

        return when (result) {
            is AppResult.Success -> {
                store.remove(write.key)
                true
            }

            is AppResult.Failure -> {
                // Only the server refusing the change itself is a reason to stop trying.
                //
                // Being asleep is not, and neither is a session that could not be renewed.
                // Those look different but they are the same event: the token expires while
                // the phone is idle, the write wakes a sleeping server, the refresh that goes
                // with it times out too, and a plain 401 comes back. Marked failed, that is
                // somebody being told the thing they just wrote down was thrown away because
                // the free tier had gone to sleep -- which is the exact moment this queue
                // exists for. Held instead: it goes out when the server answers, or when the
                // person signs in again, and either way nothing they typed is lost.
                if (result.error.isRetryable || result.error.isAuthFailure) {
                    false
                } else {
                    store.replace(write.blocked(result.error.message))
                    true
                }
            }
        }
    }
}
