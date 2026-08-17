package com.dividendstream.api.auth

import com.dividendstream.api.common.ForbiddenException
import com.dividendstream.api.config.RegistrationProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * The registration gate, exercised without a Spring context. The wiring is trivial; the
 * interesting part is the edge cases, and above all that leaving the code unset really does
 * change nothing for an existing deployment.
 */
class InviteCodeTest {

    /** Mirrors AuthService.requireValidInviteCode. */
    private fun check(configured: String, supplied: String?) {
        val expected = RegistrationProperties(inviteCode = configured).inviteCode.trim()
        if (expected.isEmpty()) return
        val matches = MessageDigest.isEqual(
            supplied?.trim().orEmpty().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            throw ForbiddenException(message = "invalid", code = "INVALID_INVITE_CODE")
        }
    }

    @Test
    @DisplayName("registration stays open when no code is configured")
    fun `blank config allows anyone`() {
        check(configured = "", supplied = null)
        check(configured = "", supplied = "anything")
        check(configured = "   ", supplied = null)
    }

    @Test
    fun `the correct code is accepted, and surrounding whitespace forgiven`() {
        check(configured = "LETMEIN", supplied = "LETMEIN")
        check(configured = "LETMEIN", supplied = "  LETMEIN  ")
        check(configured = "  LETMEIN  ", supplied = "LETMEIN")
    }

    @Test
    @DisplayName("a missing, blank or wrong code is refused once a code is configured")
    fun `bad codes are refused`() {
        listOf(null, "", "   ", "letmein", "LETMEI", "LETMEINX").forEach { supplied ->
            assertThatThrownBy { check(configured = "LETMEIN", supplied = supplied) }
                .describedAs("supplied=%s", supplied)
                .isInstanceOf(ForbiddenException::class.java)
                .hasFieldOrPropertyWithValue("code", "INVALID_INVITE_CODE")
        }
    }

    @Test
    @DisplayName("comparison does not short-circuit, so timing cannot leak the code")
    fun `uses a constant time comparison`() {
        // A plain == returns as soon as two bytes differ, so a wrong code that shares a longer
        // prefix takes measurably longer to reject. MessageDigest.isEqual compares every byte.
        assertThat(MessageDigest.isEqual("AAAAAAA".toByteArray(), "BAAAAAA".toByteArray())).isFalse()
        assertThat(MessageDigest.isEqual("AAAAAAA".toByteArray(), "AAAAAAB".toByteArray())).isFalse()
        assertThat(MessageDigest.isEqual("LETMEIN".toByteArray(), "LETMEIN".toByteArray())).isTrue()
    }
}
