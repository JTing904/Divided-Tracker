package com.dividendstream.api.security

import java.util.UUID

/**
 * The authenticated caller, injected into controllers with `@AuthenticationPrincipal`.
 *
 * Every user-scoped query is filtered by [userId] taken from here -- never from a path or
 * body parameter -- which is what prevents one user reading another user's portfolio.
 */
data class AuthPrincipal(
    val userId: UUID,
    val email: String,
)
