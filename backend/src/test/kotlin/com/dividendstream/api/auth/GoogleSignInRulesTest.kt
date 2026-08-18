package com.dividendstream.api.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The decision table behind Google sign-in, exercised without Spring or a database.
 *
 * Each branch here is a security decision rather than a convenience: which account a token maps
 * to, whether an existing account may be taken over, and whether a new one may be created at
 * all. Mirrors AuthService.completeGoogleSignIn.
 */
class GoogleSignInRulesTest {

    private data class Account(val email: String, val googleSubject: String?, val hasPassword: Boolean)

    private sealed interface Outcome {
        data class SignedIn(val email: String) : Outcome
        data class Linked(val email: String) : Outcome
        data class Created(val email: String, val name: String) : Outcome
        data class Refused(val code: String) : Outcome
    }

    /** The rules under test, over an in-memory set of accounts. */
    private fun signIn(
        accounts: List<Account>,
        identity: GoogleIdentity,
        inviteCode: String?,
        requiredInviteCode: String = "",
    ): Outcome {
        accounts.firstOrNull { it.googleSubject == identity.subject }
            ?.let { return Outcome.SignedIn(it.email) }

        accounts.firstOrNull { it.email.equals(identity.email, ignoreCase = true) }?.let {
            if (!identity.emailVerified) return Outcome.Refused("GOOGLE_EMAIL_UNVERIFIED")
            return Outcome.Linked(it.email)
        }

        if (requiredInviteCode.isNotEmpty() && inviteCode?.trim() != requiredInviteCode) {
            return Outcome.Refused("INVALID_INVITE_CODE")
        }
        return Outcome.Created(identity.email, identity.name ?: identity.email.substringBefore('@'))
    }

    private fun google(
        subject: String = "google-123",
        email: String = "someone@gmail.com",
        verified: Boolean = true,
        name: String? = "Someone",
    ) = GoogleIdentity(subject, email, verified, name)

    @Test
    @DisplayName("a returning user is matched on the Google subject, not the email")
    fun `returning user is found by subject`() {
        // The address on the account is deliberately different: the subject is what identifies
        // the person, so a Google account whose email changed still lands on the right row.
        val accounts = listOf(Account("old-address@gmail.com", "google-123", hasPassword = false))

        val outcome = signIn(accounts, google(subject = "google-123", email = "new-address@gmail.com"), null)

        assertThat(outcome).isEqualTo(Outcome.SignedIn("old-address@gmail.com"))
    }

    @Test
    @DisplayName("a returning user needs no invite code, even when one is required")
    fun `returning user is not asked for an invite`() {
        val accounts = listOf(Account("someone@gmail.com", "google-123", hasPassword = false))

        val outcome = signIn(accounts, google(), inviteCode = null, requiredInviteCode = "LETMEIN")

        assertThat(outcome).isEqualTo(Outcome.SignedIn("someone@gmail.com"))
    }

    @Test
    @DisplayName("an existing password account is linked when Google verified the address")
    fun `verified email links to the existing account`() {
        val accounts = listOf(Account("someone@gmail.com", null, hasPassword = true))

        val outcome = signIn(accounts, google(verified = true), inviteCode = null, requiredInviteCode = "LETMEIN")

        // Linking is not account creation, so the invite code does not apply.
        assertThat(outcome).isEqualTo(Outcome.Linked("someone@gmail.com"))
    }

    @Test
    @DisplayName("an unverified address cannot take over an existing account")
    fun `unverified email is refused`() {
        // The attack this blocks: register a Google account claiming someone else's address,
        // leave it unverified, and sign in to their portfolio.
        val accounts = listOf(Account("victim@gmail.com", null, hasPassword = true))

        val outcome = signIn(accounts, google(email = "victim@gmail.com", verified = false), null)

        assertThat(outcome).isEqualTo(Outcome.Refused("GOOGLE_EMAIL_UNVERIFIED"))
    }

    @Test
    @DisplayName("creating an account through Google still needs the invite code")
    fun `new account is gated`() {
        // Otherwise the endpoint undoes the gate entirely: anyone with a Google account signs up.
        assertThat(signIn(emptyList(), google(), inviteCode = null, requiredInviteCode = "LETMEIN"))
            .isEqualTo(Outcome.Refused("INVALID_INVITE_CODE"))
        assertThat(signIn(emptyList(), google(), inviteCode = "wrong", requiredInviteCode = "LETMEIN"))
            .isEqualTo(Outcome.Refused("INVALID_INVITE_CODE"))
        assertThat(signIn(emptyList(), google(), inviteCode = "LETMEIN", requiredInviteCode = "LETMEIN"))
            .isEqualTo(Outcome.Created("someone@gmail.com", "Someone"))
    }

    @Test
    fun `with no invite code configured, a new Google account is created`() {
        assertThat(signIn(emptyList(), google(), inviteCode = null))
            .isEqualTo(Outcome.Created("someone@gmail.com", "Someone"))
    }

    @Test
    @DisplayName("a Google account without a name falls back to the local part of the email")
    fun `missing name does not become blank`() {
        assertThat(signIn(emptyList(), google(name = null), inviteCode = null))
            .isEqualTo(Outcome.Created("someone@gmail.com", "someone"))
    }

    @Test
    @DisplayName("email matching ignores case, as the database's unique index does")
    fun `email match is case insensitive`() {
        val accounts = listOf(Account("Someone@Gmail.com", null, hasPassword = true))

        assertThat(signIn(accounts, google(email = "someone@gmail.com"), null))
            .isEqualTo(Outcome.Linked("Someone@Gmail.com"))
    }
}
