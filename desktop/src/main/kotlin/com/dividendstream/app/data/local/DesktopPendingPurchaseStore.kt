package com.dividendstream.app.data.local

import com.dividendstream.app.AppPaths
import com.dividendstream.app.domain.PendingPurchase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The desktop counterpart of the Android queue: same class, same behaviour, a file instead of
 * DataStore. See PendingPurchaseStore in the Android sources for why this is kept apart from
 * the snapshot cache.
 */
class PendingPurchaseStore(
    private val json: Json,
    private val directory: Path = AppPaths.state,
) {

    private val file: Path get() = directory.resolve("pending-purchases.json")
    private val mutex = Mutex()
    private val _pending = MutableStateFlow(readFromDisk())

    val pending: Flow<List<PendingPurchase>> = _pending.asStateFlow()

    suspend fun all(): List<PendingPurchase> = _pending.value

    suspend fun add(purchase: PendingPurchase) = mutate { current ->
        current.filterNot { it.idempotencyKey == purchase.idempotencyKey } + purchase
    }

    suspend fun remove(idempotencyKey: String) = mutate { current ->
        current.filterNot { it.idempotencyKey == idempotencyKey }
    }

    suspend fun replace(purchase: PendingPurchase) = mutate { current ->
        current.map { if (it.idempotencyKey == purchase.idempotencyKey) purchase else it }
    }

    private suspend fun mutate(transform: (List<PendingPurchase>) -> List<PendingPurchase>) {
        mutex.withLock {
            val next = transform(_pending.value)
            withContext(Dispatchers.IO) { writeToDisk(next) }
            _pending.value = next
        }
    }

    private fun readFromDisk(): List<PendingPurchase> = runCatching {
        if (!Files.exists(file)) return emptyList()
        json.decodeFromString(SERIALIZER, Files.readString(file))
    }.getOrDefault(emptyList())

    /**
     * Written to a temporary file and moved into place, so an interrupted write cannot leave a
     * half-file behind. A truncated queue is a lost purchase.
     */
    private fun writeToDisk(purchases: List<PendingPurchase>) {
        Files.createDirectories(directory)
        val temporary = directory.resolve("pending-purchases.json.tmp")
        Files.writeString(temporary, json.encodeToString(SERIALIZER, purchases))
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val SERIALIZER = ListSerializer(PendingPurchase.serializer())
    }
}
