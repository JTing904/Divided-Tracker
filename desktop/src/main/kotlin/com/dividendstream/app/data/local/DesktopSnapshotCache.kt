package com.dividendstream.app.data.local

import com.dividendstream.app.AppPaths
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.PortfolioDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** A value read back from disk, with enough context to tell the user how old it is. */
data class CachedSnapshot<T>(
    val value: T,
    val cachedAt: Instant,
    val clockOffsetMillis: Long,
)

/**
 * Desktop replacement for the DataStore-backed cache, with the same public surface.
 *
 * The server clock offset is stored alongside the data for the same reason as on Android:
 * the live counter is a function of time, so restoring the value without restoring the clock
 * correction would make a cold, offline start show a subtly wrong figure.
 */
class SnapshotCache(
    private val json: Json,
    private val directory: Path = AppPaths.state,
) {

    suspend fun saveLive(snapshot: LiveDividendDto, clockOffsetMillis: Long) = withContext(Dispatchers.IO) {
        write("live.json", json.encodeToString(LiveDividendDto.serializer(), snapshot))
        write("clock-offset", clockOffsetMillis.toString())
    }

    suspend fun readLive(): CachedSnapshot<LiveDividendDto>? = withContext(Dispatchers.IO) {
        read("live.json") { json.decodeFromString(LiveDividendDto.serializer(), it) }
    }

    suspend fun savePortfolio(portfolio: PortfolioDto) = withContext(Dispatchers.IO) {
        write("portfolio.json", json.encodeToString(PortfolioDto.serializer(), portfolio))
    }

    suspend fun readPortfolio(): CachedSnapshot<PortfolioDto>? = withContext(Dispatchers.IO) {
        read("portfolio.json") { json.decodeFromString(PortfolioDto.serializer(), it) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        listOf("live.json", "portfolio.json", "clock-offset").forEach {
            Files.deleteIfExists(directory.resolve(it))
        }
        Unit
    }

    private fun <T> read(name: String, decode: (String) -> T): CachedSnapshot<T>? {
        val path = directory.resolve(name)
        if (!Files.exists(path)) return null
        // A cache written by an older app version may no longer parse; treat it as absent.
        val value = runCatching { decode(Files.readString(path)) }.getOrNull() ?: return null
        return CachedSnapshot(
            value = value,
            cachedAt = runCatching { Files.getLastModifiedTime(path).toInstant() }
                .getOrDefault(Instant.EPOCH),
            clockOffsetMillis = runCatching {
                Files.readString(directory.resolve("clock-offset")).trim().toLong()
            }.getOrDefault(0L),
        )
    }

    private fun write(name: String, content: String) {
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        val temp = directory.resolve("$name.tmp")
        Files.writeString(temp, content)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
