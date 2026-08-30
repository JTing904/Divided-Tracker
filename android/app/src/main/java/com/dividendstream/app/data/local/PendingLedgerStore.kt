package com.dividendstream.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dividendstream.app.domain.PendingLedgerWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.ledgerQueueDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pending_ledger_writes")

/**
 * Ledger changes entered but not yet accepted by the server, kept on the device.
 *
 * Kept apart from the snapshot cache for the same reason the purchase queue is: the cache
 * holds figures the server already knows and may be thrown away at any moment, while this
 * holds intent the server has *not* heard. Losing it loses something a person believes they
 * wrote down. Sign-out clears the cache; it does not clear this.
 *
 * Order is kept. A fund has to exist before money can move into it, and the queue is drained
 * oldest first for exactly that reason.
 */
class PendingLedgerStore(
    private val context: Context,
    private val json: Json,
) {

    val pending: Flow<List<PendingLedgerWrite>> =
        context.ledgerQueueDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun all(): List<PendingLedgerWrite> = pending.first()

    /** Keyed, so re-queueing the same change replaces it rather than sending it twice. */
    suspend fun add(write: PendingLedgerWrite) = mutate { current ->
        current.filterNot { it.key == write.key } + write
    }

    suspend fun remove(key: String) = mutate { current -> current.filterNot { it.key == key } }

    suspend fun replace(write: PendingLedgerWrite) = mutate { current ->
        current.map { if (it.key == write.key) write else it }
    }

    /**
     * Drops a queued change for [id] that has not gone out yet.
     *
     * Deleting something the server has never heard of would be answered with "not found", so
     * the two cancel here instead: the change simply never happened.
     */
    suspend fun cancel(id: String): Boolean {
        val existing = all().any { it.key == id }
        if (existing) remove(id)
        return existing
    }

    private suspend fun mutate(transform: (List<PendingLedgerWrite>) -> List<PendingLedgerWrite>) {
        context.ledgerQueueDataStore.edit { prefs ->
            prefs[KEY] = encode(transform(decode(prefs[KEY])))
        }
    }

    private fun decode(raw: String?): List<PendingLedgerWrite> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }
            .orEmpty()

    private fun encode(writes: List<PendingLedgerWrite>): String =
        json.encodeToString(SERIALIZER, writes)

    private companion object {
        val KEY = stringPreferencesKey("pending_ledger_writes")
        val SERIALIZER = ListSerializer(PendingLedgerWrite.serializer())
    }
}
