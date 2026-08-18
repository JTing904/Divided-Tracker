package com.dividendstream.app.ui.auth

import com.dividendstream.app.data.remote.GoogleAuthAttempt
import com.dividendstream.app.data.remote.GoogleConfigDto

/**
 * Obtains something from Google that the backend will accept, or nothing.
 *
 * Two implementations exist, one per client, because the platforms have genuinely different
 * answers: Android has a system account picker, the desktop has a browser. Everything above
 * this interface -- the login screen, its view model, the repository -- is shared and never
 * learns which one it is talking to.
 *
 * The interface lives apart from either implementation because the Android file is excluded
 * from the desktop build; leaving the type in it would take the contract away with it.
 */
interface GoogleSignInLauncher {

    /** False where the platform cannot do it at all, so the button is never offered. */
    val isSupported: Boolean

    /**
     * Returns null when the person backed out, which is not a failure and must not be shown as
     * one. Throws [GoogleSignInFailed] when the attempt genuinely broke.
     */
    suspend fun launch(config: GoogleConfigDto): GoogleAuthAttempt?
}

/** Carries wording already fit to show; the underlying exception never reaches the screen. */
class GoogleSignInFailed(message: String, cause: Throwable? = null) : Exception(message, cause)
