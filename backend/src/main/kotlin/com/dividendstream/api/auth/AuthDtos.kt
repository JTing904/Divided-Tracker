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

/** Android: Credential Manager hands back an ID token directly. */
data class GoogleSignInRequest(
    @field:NotBlank(message = "Google token is required")
    val idToken: String,

    /** Required only for a *new* account, and only when the server requires one. */
    @field:Size(max = 100, message = "Invite code is too long")
    val inviteCode: String? = null,
)

/**
 * Desktop: the browser hands back an authorisation code, redeemed here rather than on the
 * client so the client secret never ships inside an installable binary.
 */
data class GoogleDesktopSignInRequest(
    @field:NotBlank(message = "Authorisation code is required")
    val code: String,

    @field:NotBlank(message = "Code verifier is required")
    val codeVerifier: String,

    @field:NotBlank(message = "Redirect URI is required")
    val redirectUri: String,

    @field:Size(max = 100, message = "Invite code is too long")
    val inviteCode: String? = null,
)

/**
 * What a client needs to start a Google sign-in. Served rather than baked into each build, so
 * the client IDs have one home. None of it is secret: [desktopClientId] travels in the
 * browser's address bar, and the Android one is readable in any copy of the app.
 */
data class GoogleConfigResponse(
    val enabled: Boolean,
    val desktopEnabled: Boolean,
    val desktopClientId: String?,
)
