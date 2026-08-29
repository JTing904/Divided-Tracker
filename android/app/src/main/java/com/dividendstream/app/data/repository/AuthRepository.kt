package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.map
import com.dividendstream.app.data.local.Session
import com.dividendstream.app.data.local.SessionStore
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.AuthResponse
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.GoogleAuthAttempt
import com.dividendstream.app.data.remote.GoogleConfigDto
import com.dividendstream.app.data.remote.GoogleDesktopSignInRequest
import com.dividendstream.app.data.remote.GoogleSignInRequest
import com.dividendstream.app.data.remote.LoginRequest
import com.dividendstream.app.data.remote.RegisterRequest
import com.dividendstream.app.data.remote.UpdateProfileRequest
import com.dividendstream.app.data.remote.UserProfileDto
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
     * Whether this server offers Google sign-in, and what the desktop flow needs to start one.
     *
     * Served rather than compiled in, so the client IDs have a single home and changing one
     * does not mean rebuilding and redistributing both applications.
     */
    suspend fun googleConfig(): AppResult<GoogleConfigDto> = apiCall(json) { api.googleConfig() }

    /**
     * Exchanges whatever the platform obtained from Google for a session here.
     *
     * The invite code rides along because the backend may be about to create an account, and
     * only it can tell: the user does not know whether this Google account is already known.
     */
    suspend fun signInWithGoogle(
        attempt: GoogleAuthAttempt,
        inviteCode: String,
    ): AppResult<Session> {
        val code = inviteCode.trim().takeIf { it.isNotEmpty() }
        return apiCall(json) {
            when (attempt) {
                is GoogleAuthAttempt.IdToken ->
                    api.googleSignIn(GoogleSignInRequest(attempt.idToken, code))

                is GoogleAuthAttempt.AuthorizationCode ->
                    api.googleDesktopSignIn(
                        GoogleDesktopSignInRequest(
                            code = attempt.code,
                            codeVerifier = attempt.codeVerifier,
                            redirectUri = attempt.redirectUri,
                            inviteCode = code,
                        ),
                    )
            }
        }
            .also { it.persistOnSuccess() }
            .map { it.toSession() }
    }

    /**
     * Clears local state whether or not the server call succeeds. Failing to reach the
     * backend must not leave the user apparently signed in on the device.
     */
    suspend fun logout() {
        apiCall(json) { api.logout() }
        sessionStore.clear()
        snapshotCache.clear()
    }

    /** The account as the server has it, which is the copy that matters. */
    suspend fun profile(): AppResult<UserProfileDto> = apiCall(json) { api.profile() }

    /**
     * Saves the account details and brings the stored session into line with them.
     *
     * The session carries the name the greeting is drawn from, so leaving it behind would show
     * somebody their old name on the very screen they changed it from -- until the next sign-in,
     * which could be weeks away. The tokens are untouched: this is not a re-authentication.
     */
    suspend fun updateProfile(name: String, baseCurrency: String): AppResult<UserProfileDto> {
        val result = apiCall(json) {
            api.updateProfile(UpdateProfileRequest(name = name.trim(), baseCurrency = baseCurrency))
        }
        if (result is AppResult.Success) {
            sessionStore.load()?.let { session ->
                sessionStore.save(
                    session.copy(
                        userName = result.data.name,
                        userEmail = result.data.email,
                        baseCurrency = result.data.baseCurrency,
                    ),
                )
            }
        }
        return result
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
