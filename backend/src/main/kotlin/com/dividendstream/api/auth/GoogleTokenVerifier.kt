package com.dividendstream.api.auth

import com.dividendstream.api.common.UnauthorizedException
import com.dividendstream.api.config.GoogleProperties
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.stereotype.Component

/**
 * Turns a Google ID token into a [GoogleIdentity], or refuses it.
 *
 * The verification itself is Google's: signature against their published keys, issuer,
 * audience and expiry, with the key set fetched and cached by the library. Writing that by
 * hand would mean getting `kid` selection and algorithm pinning right, and getting either
 * wrong turns "prove who you are" into "tell me who you are".
 *
 * The audience check is the load-bearing one. Any application can obtain a valid, correctly
 * signed Google ID token for its own users; what makes one of them a login *here* is that
 * Google minted it for one of our client IDs.
 */
@Component
class GoogleTokenVerifier(private val properties: GoogleProperties) {

    private val verifier: GoogleIdTokenVerifier? by lazy {
        val audiences = properties.allowedAudiences.filter { it.isNotBlank() }
        if (audiences.isEmpty()) {
            null
        } else {
            GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(audiences)
                .build()
        }
    }

    fun verify(idToken: String): GoogleIdentity {
        val configured = verifier
            ?: throw UnauthorizedException(
                "Google sign-in is not available on this server.",
                "GOOGLE_SIGN_IN_UNAVAILABLE",
            )

        // A malformed token throws rather than returning null, and a well-formed one that
        // fails any check returns null. Both mean the same thing to a caller, and neither
        // reason is safe to relay.
        val token = runCatching { configured.verify(idToken) }.getOrNull()
            ?: throw UnauthorizedException(
                "That Google sign-in could not be verified. Please try again.",
                "GOOGLE_TOKEN_INVALID",
            )

        val payload = token.payload
        val email = payload.email?.trim()?.lowercase()
        if (email.isNullOrEmpty()) {
            // Every scope this application requests includes the address, so its absence means
            // the token is not the one we asked for.
            throw UnauthorizedException(
                "That Google account did not share an email address.",
                "GOOGLE_EMAIL_MISSING",
            )
        }

        return GoogleIdentity(
            subject = payload.subject,
            email = email,
            emailVerified = payload.emailVerified == true,
            name = (payload["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
