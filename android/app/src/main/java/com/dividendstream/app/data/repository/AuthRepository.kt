package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.map
import com.dividendstream.app.data.local.Session
import com.dividendstream.app.data.local.SessionStore
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.AuthResponse
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.LoginRequest
import com.dividendstream.app.data.remote.RegisterRequest
import com.dividendstream.app.data.remote.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class AuthRepository(
    private val api: DividendStreamApi,
    private val sessionStore: SessionStore,
    private val snapshotCache: SnapshotCache,
    private val json: Json,
) {

    val sessions: Flow<Session?> = sessionStore.sessions

    suspend fun restoreSession(): Session? = sessionStore.load()

    suspend fun register(
        name: String,
        email: String,
        password: String,
        inviteCode: String,
    ): AppResult<Session> =
        apiCall(json) {
            api.register(
                RegisterRequest(
                    name = name.trim(),
                    email = email.trim(),
                    password = password,
                    // Sent as null when empty, so an open server sees no difference.
                    inviteCode = inviteCode.trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
            .also { it.persistOnSuccess() }
            .map { it.toSession() }

    suspend fun login(email: String, password: String): AppResult<Session> =
        apiCall(json) { api.login(LoginRequest(email.trim(), password)) }
            .also { it.persistOnSuccess() }
            .map { it.toSession() }

    /**
     * Clears local state whether or not the server call succeeds. Failing to reach the
     * backend must not leave the user apparently signed in on the device.
     */
    suspend fun logout() {
        apiCall(json) { api.logout() }
        sessionStore.clear()
        snapshotCache.clear()
    }

    private suspend fun AppResult<AuthResponse>.persistOnSuccess() {
        if (this is AppResult.Success) sessionStore.save(data.toSession())
    }

    private fun AuthResponse.toSession() = Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = user.id,
        userName = user.name,
        userEmail = user.email,
        baseCurrency = user.baseCurrency,
    )
}
