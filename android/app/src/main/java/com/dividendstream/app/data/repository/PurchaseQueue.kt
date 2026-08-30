package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.local.PendingPurchaseStore
import com.dividendstream.app.data.remote.ServerAvailability
import com.dividendstream.app.domain.PendingPurchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Takes a purchase off the user's hands and gets it to the server eventually.
 *
 * The backend sleeps between uses and takes about two minutes to wake, and a purchase is not
 * something worth watching a spinner for. So every purchase is written to the device first and
 * sent afterwards -- one path, whether the server happens to be up or not, rather than a guess
 * about which it is.
 *
 * Retrying a write is only safe because the server recognises repeats: each purchase carries an
 * idempotency key generated once, here, and kept for the life of the queue entry. Without that
 * this class would be a way to buy the same shares twice.
 */
class PurchaseQueue(
    private val store: PendingPurchaseStore,
    private val portfolioRepository: PortfolioRepository,
    private val availability: ServerAvailability,
    private val scope: CoroutineScope,
) {

    val pending: Flow<List<PendingPurchase>> = store.pending

    /** One drain at a time; two would present the same key concurrently for no benefit. */
    private val draining = Mutex()

    init {
        // Anything left from a previous run goes out as soon as there is somewhere to send it.
        drain()
        scope.launch {
            availability.status
                .filterIsInstance<ServerAvailability.Status.Awake>()
                .collect { drain() }
        }
    }

    /**
     * Records the purchase and starts trying. Returns as soon as it is safely on the device,
     * which is the point: the person is free to leave.
     */
    suspend fun submit(
        symbol: String,
        companyName: String,
        quantity: BigDecimal,
        averagePrice: BigDecimal,
    ) {
        store.add(
            PendingPurchase(
                idempotencyKey = UUID.randomUUID().toString(),
                symbol = symbol,
                companyName = companyName,
                quantity = quantity,
                averagePrice = averagePrice,
                queuedAt = Instant.now(),
            ),
        )
        drain()
    }

    /** Forgets a purchase the server refused outright. Only the user can decide to drop one. */
    suspend fun discard(idempotencyKey: String) = store.remove(idempotencyKey)

    /** Clears the refusal so a blocked purchase is tried again, after the cause is fixed. */
    suspend fun retry(idempotencyKey: String) {
        store.all().firstOrNull { it.idempotencyKey == idempotencyKey }
            ?.let { store.replace(it.copy(failure = null)) }
        drain()
    }

    fun drain() {
        scope.launch {
            draining.withLock {
                store.all().filterNot { it.isBlocked }.forEach { send(it) }
            }
        }
    }

    private suspend fun send(purchase: PendingPurchase) {
        val result = portfolioRepository.addHolding(
            symbol = purchase.symbol,
            quantity = purchase.quantity,
            averagePrice = purchase.averagePrice,
            idempotencyKey = purchase.idempotencyKey,
        )

        when (result) {
            is AppResult.Success -> store.remove(purchase.idempotencyKey)

            is AppResult.Failure -> {
                // A retryable failure is the ordinary case -- the server is asleep, or the
                // phone is off the network -- and the purchase simply stays queued. So is a
                // session that could not be renewed, which is what a sleeping server does to
                // an expired token. Anything else will fail identically forever, so it stops
                // and waits to be seen rather than retrying silently until the end of time.
                if (!result.error.isRetryable && !result.error.isAuthFailure) {
                    store.replace(purchase.copy(failure = result.error.message))
                }
            }
        }
    }
}
