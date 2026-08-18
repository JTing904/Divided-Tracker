package com.dividendstream.api.auth

import com.dividendstream.api.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request))

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse =
        authService.login(request)

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthResponse =
        authService.refresh(request)

    /** What a client needs to begin a Google sign-in. Public, and contains nothing secret. */
    @GetMapping("/google/config")
    fun googleConfig(): GoogleConfigResponse = authService.googleConfig()

    /** Android: an ID token obtained through Credential Manager. */
    @PostMapping("/google")
    fun google(@Valid @RequestBody request: GoogleSignInRequest): AuthResponse =
        authService.signInWithGoogle(request)

    /** Desktop: an authorisation code from the browser, redeemed on this side. */
    @PostMapping("/google/desktop")
    fun googleDesktop(@Valid @RequestBody request: GoogleDesktopSignInRequest): AuthResponse =
        authService.signInWithGoogleCode(request)

    /** Revokes every refresh token for the caller. Access tokens expire on their own. */
    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal principal: AuthPrincipal): ResponseEntity<Void> {
        authService.logout(principal.userId)
        return ResponseEntity.noContent().build()
    }
}
