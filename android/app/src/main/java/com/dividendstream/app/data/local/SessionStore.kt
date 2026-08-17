package com.dividendstream.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "dividend_stream_session")

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userName: String,
    val userEmail: String,
    val baseCurrency: String,
)

/**
 * Persists the signed-in session so the app opens straight onto the dashboard.
 *
 * A synchronous [current] snapshot is kept in memory because OkHttp's interceptor and
 * authenticator run on network threads and cannot suspend to read DataStore.
 *
 * Note for a later hardening pass: tokens sit in app-private DataStore, which relies on
 * Android's file-based encryption rather than the keystore. Access tokens are short-lived
 * and refresh tokens are revocable server-side, so this is a reasonable MVP position, but
 * moving to a keystore-backed store is worth doing before release.
 */
class SessionStore(private val context: Context) {

    @Volatile
    private var cache: Session? = null

    /** Last known session, readable without suspending. Null when signed out. */
    val current: Session? get() = cache

    val sessions: Flow<Session?> = context.sessionDataStore.data.map { prefs ->
        val accessToken = prefs[KEY_ACCESS] ?: return@map null
        val refreshToken = prefs[KEY_REFRESH] ?: return@map null
        Session(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = prefs[KEY_USER_ID].orEmpty(),
            userName = prefs[KEY_USER_NAME].orEmpty(),
            userEmail = prefs[KEY_USER_EMAIL].orEmpty(),
            baseCurrency = prefs[KEY_CURRENCY] ?: "MYR",
        ).also { cache = it }
    }

    suspend fun load(): Session? = sessions.first()

    suspend fun save(session: Session) {
        cache = session
        context.sessionDataStore.edit { prefs ->
            prefs[KEY_ACCESS] = session.accessToken
            prefs[KEY_REFRESH] = session.refreshToken
            prefs[KEY_USER_ID] = session.userId
            prefs[KEY_USER_NAME] = session.userName
            prefs[KEY_USER_EMAIL] = session.userEmail
            prefs[KEY_CURRENCY] = session.baseCurrency
        }
    }

    suspend fun clear() {
        cache = null
        context.sessionDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_ACCESS = stringPreferencesKey("access_token")
        val KEY_REFRESH = stringPreferencesKey("refresh_token")
        val KEY_USER_ID = stringPreferencesKey("user_id")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        val KEY_CURRENCY = stringPreferencesKey("base_currency")
    }
}
