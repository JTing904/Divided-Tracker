package com.dividendstream.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.PortfolioDto
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.time.Instant

private val Context.cacheDataStore by preferencesDataStore(name = "dividend_stream_cache")

/** A value read back from disk, with enough context to tell the user how old it is. */
data class CachedSnapshot<T>(
    val value: T,
    val cachedAt: Instant,
    val clockOffsetMillis: Long,
)

/**
 * Last-known-good copies of the two screens worth seeing offline.
 *
 * The server clock offset is stored alongside the data: the live counter is a function of
 * time, so restoring the value without restoring the clock correction would make a cold,
 * offline start show a subtly wrong figure.
 */
class SnapshotCache(
    private val context: Context,
    private val json: Json,
) {

    suspend fun saveLive(snapshot: LiveDividendDto, clockOffsetMillis: Long) {
        context.cacheDataStore.edit { prefs ->
            prefs[KEY_LIVE] = json.encodeToString(LiveDividendDto.serializer(), snapshot)
            prefs[KEY_LIVE_AT] = System.currentTimeMillis()
            prefs[KEY_CLOCK_OFFSET] = clockOffsetMillis
        }
    }

    suspend fun readLive(): CachedSnapshot<LiveDividendDto>? = read(KEY_LIVE, KEY_LIVE_AT) {
        json.decodeFromString(LiveDividendDto.serializer(), it)
    }

    suspend fun savePortfolio(portfolio: PortfolioDto) {
        context.cacheDataStore.edit { prefs ->
            prefs[KEY_PORTFOLIO] = json.encodeToString(PortfolioDto.serializer(), portfolio)
            prefs[KEY_PORTFOLIO_AT] = System.currentTimeMillis()
        }
    }

    suspend fun readPortfolio(): CachedSnapshot<PortfolioDto>? = read(KEY_PORTFOLIO, KEY_PORTFOLIO_AT) {
        json.decodeFromString(PortfolioDto.serializer(), it)
    }

    suspend fun saveLedger(ledger: LedgerDto) {
        context.cacheDataStore.edit { prefs ->
            prefs[KEY_LEDGER] = json.encodeToString(LedgerDto.serializer(), ledger)
            prefs[KEY_LEDGER_AT] = System.currentTimeMillis()
        }
    }

    suspend fun readLedger(): CachedSnapshot<LedgerDto>? = read(KEY_LEDGER, KEY_LEDGER_AT) {
        json.decodeFromString(LedgerDto.serializer(), it)
    }

    suspend fun clear() {
        context.cacheDataStore.edit { it.clear() }
    }

    private suspend fun <T> read(
        valueKey: androidx.datastore.preferences.core.Preferences.Key<String>,
        timestampKey: androidx.datastore.preferences.core.Preferences.Key<Long>,
        decode: (String) -> T,
    ): CachedSnapshot<T>? {
        val prefs = context.cacheDataStore.data.first()
        val raw = prefs[valueKey] ?: return null
        // A cache written by an older app version may no longer parse; treat it as absent.
        val value = runCatching { decode(raw) }.getOrNull() ?: return null
        return CachedSnapshot(
            value = value,
            cachedAt = Instant.ofEpochMilli(prefs[timestampKey] ?: 0L),
            clockOffsetMillis = prefs[KEY_CLOCK_OFFSET] ?: 0L,
        )
    }

    private companion object {
        val KEY_LIVE = stringPreferencesKey("live_snapshot")
        val KEY_LIVE_AT = longPreferencesKey("live_snapshot_at")
        val KEY_PORTFOLIO = stringPreferencesKey("portfolio_snapshot")
        val KEY_PORTFOLIO_AT = longPreferencesKey("portfolio_snapshot_at")
        val KEY_LEDGER = stringPreferencesKey("ledger_snapshot")
        val KEY_LEDGER_AT = longPreferencesKey("ledger_snapshot_at")
        val KEY_CLOCK_OFFSET = longPreferencesKey("server_clock_offset")
    }
}
