package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.data.remote.DividendHistoryDto
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.UpcomingDividendsDto
import com.dividendstream.app.data.remote.apiCall
import kotlinx.serialization.json.Json

class DividendRepository(
    private val api: DividendStreamApi,
    private val snapshotCache: SnapshotCache,
    private val serverClock: ServerClock,
    private val json: Json,
) {

    /**
     * Fetches the live snapshot, syncing the server clock and saving a copy for offline use.
     *
     * On a network failure the saved copy is returned instead, marked stale -- the counter
     * can keep running from stored timestamps without the server. An *authentication*
     * failure is never masked this way: that has to reach the UI so the user is signed out.
     */
    suspend fun live(): AppResult<Cached<LiveDividendDto>> =
        when (val result = apiCall(json) { api.liveDividends() }) {
            is AppResult.Success -> {
                serverClock.syncTo(result.data.serverTime)
                snapshotCache.saveLive(result.data, serverClock.offset)
                AppResult.Success(Cached(result.data))
            }

            is AppResult.Failure -> {
                if (result.error.isAuthFailure) {
                    result
                } else {
                    val cached = snapshotCache.readLive()
                    if (cached == null) {
                        result
                    } else {
                        serverClock.restore(cached.clockOffsetMillis)
                        AppResult.Success(
                            Cached(
                                value = cached.value,
                                isStale = true,
                                cachedAt = cached.cachedAt,
                                staleError = result.error,
                            ),
                        )
                    }
                }
            }
        }

    /**
     * The saved snapshot, read without touching the network.
     *
     * Exists so a screen can paint *before* the request rather than after it fails. The
     * accumulating figure is derived from stored timestamps, so a saved copy is not a frozen
     * screenshot -- it keeps counting, at the right rate, from the right value. Waiting for a
     * sleeping server before showing any of that turns a cold start into a blank screen for
     * two minutes, which is the whole reason this exists.
     *
     * Marked stale, because it is: the caller should say so and then go and check.
     */
    suspend fun cachedLive(): Cached<LiveDividendDto>? =
        snapshotCache.readLive()?.let { cached ->
            serverClock.restore(cached.clockOffsetMillis)
            Cached(value = cached.value, isStale = true, cachedAt = cached.cachedAt)
        }

    suspend fun upcoming(): AppResult<UpcomingDividendsDto> =
        apiCall(json) { api.upcomingDividends() }
            .also { if (it is AppResult.Success) serverClock.syncTo(it.data.serverTime) }

    suspend fun history(): AppResult<DividendHistoryDto> = apiCall(json) { api.dividendHistory() }

    suspend fun detail(id: String): AppResult<DividendDto> = apiCall(json) { api.dividendDetail(id) }
}
