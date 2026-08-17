package com.dividendstream.api.auth

import com.dividendstream.api.user.UserProfileResponse
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class RegisterRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(max = 120, message = "Name is too long")
    val name: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Enter a valid email address")
    @field:Size(max = 255, message = "Email is too long")
    val email: String,

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 8, max = 100, message = "Password must be at least 8 characters")
    val password: String,

    /** Required only when the server is configured with an invite code. */
    @field:Size(max = 100, message = "Invite code is too long")
    val inviteCode: String? = null,
)

data class LoginRequest(
    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Enter a valid email address")
    val email: String,

    @field:NotBlank(message = "Password is required")
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank(message = "Refresh token is required")
    val refreshToken: String,
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    /** When [accessToken] stops being accepted; the client refreshes shortly before this. */
    val accessTokenExpiresAt: Instant,
    val user: UserProfileResponse,
)
