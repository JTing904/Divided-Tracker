package com.dividendstream.app.data.repository

/**
 * A second place the same person is signed in.
 *
 * The ledger lives in Firestore and the portfolio still lives behind the API, so there are two
 * accounts and one password. The sign-in screens mirror into this so nobody is asked twice --
 * and they talk to it through an interface rather than to Firebase directly, because those
 * screens are compiled into the desktop too and the Firebase SDK is Android-only. The desktop
 * passes null and stays wholly on the API.
 *
 * Every method answers with an error code, or null for success. Deliberately not a rich
 * result: the caller has nothing to do with a failure except notice which kind it was, since
 * the person is already signed in as far as they can tell.
 */
interface SessionMirror {

    /** Null on success, "NO_SUCH_USER" when there is no account here yet. */
    suspend fun signIn(email: String, password: String): String?

    suspend fun createAccount(name: String, email: String, password: String): String?

    suspend fun signInWithGoogle(idToken: String): String?
}
