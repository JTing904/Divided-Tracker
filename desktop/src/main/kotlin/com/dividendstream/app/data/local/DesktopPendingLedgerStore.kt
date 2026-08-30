package com.dividendstream.app.data.local

import com.dividendstream.app.AppPaths
import com.dividendstream.app.domain.PendingLedgerWrite
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
 * The desktop counterpart of the Android queue store: same class, same behaviour, a file
 * instead of DataStore. See PendingLedgerStore in the Android sources for why this is kept
 * apart from the snapshot cache.
 */
class PendingLedgerStore(
    private val json: Json,
    private val directory: Path = AppPaths.state,
) {

    private val file: Path get() = directory.resolve("pending-ledger.json")
    private val mutex = Mutex()
    private val _pending = MutableStateFlow(readFromDisk())

    val pending: Flow<List<PendingLedgerWrite>> = _pending.asStateFlow()

    suspend fun all(): List<PendingLedgerWrite> = _pending.value

    suspend fun add(write: PendingLedgerWrite) = mutate { current ->
        current.filterNot { it.key == write.key } + write
    }

    suspend fun remove(key: String) = mutate { current -> current.filterNot { it.key == key } }

    suspend fun replace(write: PendingLedgerWrite) = mutate { current ->
        current.map { if (it.key == write.key) write else it }
    }

    suspend fun cancel(id: String): Boolean {
        val existing = _pending.value.any { it.key == id }
        if (existing) remove(id)
        return existing
    }

    private suspend fun mutate(
        transform: (List<PendingLedgerWrite>) -> List<PendingLedgerWrite>,
    ) = mutex.withLock {
        val next = transform(_pending.value)
        _pending.value = next
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val temp = directory.resolve("pending-ledger.json.tmp")
            Files.writeString(temp, json.encodeToString(SERIALIZER, next))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readFromDisk(): List<PendingLedgerWrite> =
        runCatching { json.decodeFromString(SERIALIZER, Files.readString(file)) }.getOrDefault(emptyList())

    private companion object {
        val SERIALIZER = ListSerializer(PendingLedgerWrite.serializer())
    }
}
