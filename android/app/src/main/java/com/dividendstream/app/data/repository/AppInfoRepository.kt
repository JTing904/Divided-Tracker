package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppVersion
import com.dividendstream.app.data.remote.AppVersionDto
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.apiCall
import com.dividendstream.app.core.dataOrNull
import kotlinx.serialization.json.Json

/**
 * Asks the backend what it is and whether this client is behind.
 *
 * Nothing here is cached and nothing is retried: the answer is only ever used to decide whether
 * to show a notice, so a failure means the notice is skipped and the user is left alone. That is
 * the right failure -- an update prompt is never urgent enough to justify an error message.
 */
class AppInfoRepository(
    private val api: DividendStreamApi,
    private val json: Json,
    /** This build's own release number, from BuildConfig. */
    private val currentVersion: String,
) {

    /** The newer release to point the user at, or null when there is nothing to say. */
    suspend fun newerRelease(): String? {
        val latest = version()?.latestClient ?: return null
        return latest.takeIf { AppVersion.isOutdated(current = currentVersion, latest = it) }
    }

    /** The raw answer, for diagnostics. Null when the backend could not be reached. */
    suspend fun version(): AppVersionDto? = apiCall(json) { api.appVersion() }.dataOrNull()
}
