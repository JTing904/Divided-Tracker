package com.dividendstream.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dividendstream.app.domain.PendingPurchase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.purchaseQueueDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pending_purchases")

/**
 * Purchases entered but not yet accepted by the server, kept on the device.
 *
 * Separate from the snapshot cache on purpose. That holds figures the server already knows and
 * can be thrown away at any time; this holds intent the server has *not* heard, and losing it
 * loses something the user believes they recorded. Sign-out clears the cache, not this.
 */
class PendingPurchaseStore(
    private val context: Context,
    private val json: Json,
) {

    val pending: Flow<List<PendingPurchase>> =
        context.purchaseQueueDataStore.data.map { prefs -> decode(prefs[KEY]) }

    suspend fun all(): List<PendingPurchase> = pending.first()

    suspend fun add(purchase: PendingPurchase) = mutate { current ->
        // Keyed by the idempotency key, so re-queueing the same intent replaces rather than
        // duplicates it -- the queue must never be the thing that causes a double purchase.
        current.filterNot { it.idempotencyKey == purchase.idempotencyKey } + purchase
    }

    suspend fun remove(idempotencyKey: String) = mutate { current ->
        current.filterNot { it.idempotencyKey == idempotencyKey }
    }

    suspend fun replace(purchase: PendingPurchase) = mutate { current ->
        current.map { if (it.idempotencyKey == purchase.idempotencyKey) purchase else it }
    }

    private suspend fun mutate(transform: (List<PendingPurchase>) -> List<PendingPurchase>) {
        context.purchaseQueueDataStore.edit { prefs ->
            prefs[KEY] = encode(transform(decode(prefs[KEY])))
        }
    }

    private fun decode(raw: String?): List<PendingPurchase> =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(SERIALIZER, it) }.getOrNull() }
            .orEmpty()

    private fun encode(purchases: List<PendingPurchase>): String =
        json.encodeToString(SERIALIZER, purchases)

    private companion object {
        val KEY = stringPreferencesKey("pending_purchases")
        val SERIALIZER = ListSerializer(PendingPurchase.serializer())
    }
}
