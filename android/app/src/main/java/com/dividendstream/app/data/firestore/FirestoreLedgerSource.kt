package com.dividendstream.app.data.firestore

import com.dividendstream.app.domain.StoredEntry
import com.dividendstream.app.domain.StoredFlow
import com.dividendstream.app.domain.StoredFund
import com.dividendstream.app.domain.StoredLedger
import com.dividendstream.app.domain.StoredMovement
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await

/**
 * Everything one person has written down, straight out of Firestore.
 *
 * Four collections under `users/{uid}`, watched rather than fetched. A listener answers from
 * the copy on the device first and again when the server has anything to add, which is what
 * removes the wait this whole move was for: opening the app reads a local database, and the
 * network catching up is something that happens afterwards and silently.
 *
 * It also removes the need for LedgerQueue. Firestore's own offline persistence takes a write,
 * applies it locally at once, and sends it when it can -- surviving the process being killed,
 * exactly as the queue did, with the difference that a read immediately afterwards already
 * reflects it. What the queue had to be taught, this does by construction.
 */
class FirestoreLedgerSource(
    private val firestore: FirebaseFirestore,
    private val uid: String,
) {

    private val user get() = firestore.collection(USERS).document(uid)

    /**
     * The whole ledger, updated whenever any part of it changes.
     *
     * Combined rather than exposed as four flows, because every figure on the screen is
     * derived from all four together: a fund's balance depends on its movements, which depend
     * on the flows that were settled into it. Emitting them separately would show a screen
     * assembled from four different instants.
     */
    fun stream(currency: String): Flow<StoredLedger> = combine(
        watch(FLOWS) { id, data -> LedgerDocuments.flow(id, data) },
        watch(ENTRIES) { id, data -> LedgerDocuments.entry(id, data) },
        watch(FUNDS) { id, data -> LedgerDocuments.fund(id, data) },
        watch(MOVEMENTS) { id, data -> LedgerDocuments.movement(id, data) },
    ) { flows, entries, funds, movements ->
        StoredLedger(flows, entries, funds, movements, currency)
    }

    private fun <T> watch(
        collection: String,
        read: (String, Map<String, Any?>) -> T,
    ): Flow<List<T>> = callbackFlow {
        val registration: ListenerRegistration = user.collection(collection)
            // Local writes included, and that is the point: a record must appear the instant
            // it is written rather than once a sleeping network has acknowledged it.
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    // Not fatal. A listener that fails -- offline, or rules refusing -- leaves
                    // the last emission on screen rather than replacing a person's ledger with
                    // an error they cannot act on.
                    return@addSnapshotListener
                }
                trySend(snapshot.toList(read))
            }
        awaitClose { registration.remove() }
    }

    private fun <T> QuerySnapshot?.toList(read: (String, Map<String, Any?>) -> T): List<T> =
        this?.documents.orEmpty().mapNotNull { document ->
            val data = document.data ?: return@mapNotNull null
            runCatching { read(document.id, data) }.getOrNull()
        }

    // --- writing --------------------------------------------------------------
    //
    // Every write is keyed by an id the caller chose, and merges rather than replaces. Sending
    // the same change twice therefore records it once, which is what makes a retry -- Firestore
    // does its own -- safe. It is the same contract the API had, kept deliberately.

    suspend fun put(flow: StoredFlow) = put(FLOWS, flow.id, LedgerDocuments.document(flow))

    suspend fun put(entry: StoredEntry) = put(ENTRIES, entry.id, LedgerDocuments.document(entry))

    suspend fun put(fund: StoredFund) = put(FUNDS, fund.id, LedgerDocuments.document(fund))

    suspend fun put(movement: StoredMovement) =
        put(MOVEMENTS, movement.id, LedgerDocuments.document(movement))

    /**
     * Banks finished months, ignoring any that another device banked first.
     *
     * The id already carries the fund and the month, so two devices settling the same month
     * write the same document rather than two, and the loser overwrites an identical value.
     */
    suspend fun settle(movements: List<StoredMovement>) {
        if (movements.isEmpty()) return
        val batch = firestore.batch()
        movements.forEach {
            batch.set(user.collection(MOVEMENTS).document(it.id), LedgerDocuments.document(it), SetOptions.merge())
        }
        batch.commit().await()
    }

    suspend fun deleteFlow(id: String) = delete(FLOWS, id)
    suspend fun deleteEntry(id: String) = delete(ENTRIES, id)
    suspend fun deleteMovement(id: String) = delete(MOVEMENTS, id)

    /**
     * Removes a fund and everything ever moved into or out of it.
     *
     * Firestore does not cascade, and a movement whose fund is gone is a row that no screen
     * can explain and no total should count. Deleting the two together is the only way the
     * balance stays a sum over things a person can actually see.
     */
    suspend fun deleteFund(id: String) {
        val orphans = user.collection(MOVEMENTS).whereEqualTo("fundId", id).get().await()
        val batch = firestore.batch()
        orphans.documents.forEach { batch.delete(it.reference) }
        batch.delete(user.collection(FUNDS).document(id))
        batch.commit().await()
    }

    private suspend fun put(collection: String, id: String, data: Map<String, Any?>) {
        user.collection(collection).document(id).set(data, SetOptions.merge()).await()
    }

    private suspend fun delete(collection: String, id: String) {
        user.collection(collection).document(id).delete().await()
    }

    private companion object {
        const val USERS = "users"
        const val FLOWS = "cashFlows"
        const val ENTRIES = "entries"
        const val FUNDS = "funds"
        const val MOVEMENTS = "fundMovements"
    }
}
