package com.dividendstream.app.ui.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.dividendstream.app.data.remote.GoogleAuthAttempt
import com.dividendstream.app.data.remote.GoogleConfigDto
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

@Composable
fun rememberGoogleSignInLauncher(): GoogleSignInLauncher {
    val context = LocalContext.current
    return remember(context) { CredentialManagerGoogleSignIn(context) }
}

/**
 * Android's system account picker.
 *
 * `filterByAuthorizedAccounts = false` so the sheet offers every Google account on the device
 * rather than only ones that have used this app before -- which, on a first sign-in, is none,
 * and the picker would come up empty.
 */
private class CredentialManagerGoogleSignIn(private val context: Context) : GoogleSignInLauncher {

    override val isSupported: Boolean = true

    override suspend fun launch(config: GoogleConfigDto): GoogleAuthAttempt? {
        // The phone signs in through the *Web* client ID, not the Android one. Google issues
        // the token with that as its audience, which is what the backend checks; the Android
        // client ID exists to tie the app's signing certificate to the project and is never
        // named here.
        val serverClientId = config.webClientId
            ?: throw GoogleSignInFailed("Google sign-in is not set up on this server.")
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .build()

        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (cancelled: GetCredentialCancellationException) {
            return null
        } catch (ex: GetCredentialException) {
            throw GoogleSignInFailed("Google sign-in could not be completed. Please try again.", ex)
        }

        val credential = response.credential
        val type = credential.type
        if (type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleSignInFailed("Google returned a sign-in this app cannot use.")
        }

        val googleCredential = runCatching {
            GoogleIdTokenCredential.createFrom(credential.data)
        }.getOrElse {
            throw GoogleSignInFailed("Google returned a sign-in this app could not read.", it)
        }

        return GoogleAuthAttempt.IdToken(googleCredential.idToken)
    }
}
