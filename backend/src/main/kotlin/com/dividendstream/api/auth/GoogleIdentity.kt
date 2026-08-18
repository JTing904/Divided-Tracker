package com.dividendstream.api.auth

/**
 * The facts about a person that a verified Google ID token carries, and nothing else.
 *
 * Kept as a small type of our own so the rest of the application never handles a Google SDK
 * object, and so a test can supply one without minting a real signed token.
 */
data class GoogleIdentity(
    /** Google's `sub`. The account's identity; never reused, never reassigned. */
    val subject: String,
    val email: String,
    /**
     * Google's own assertion that it has verified this address belongs to the account.
     *
     * It gates account linking. Without it, anyone able to set an unverified address on a new
     * Google account could sign in and be handed the existing portfolio of whoever registered
     * that address here first.
     */
    val emailVerified: Boolean,
    val name: String?,
)
