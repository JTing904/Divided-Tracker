package com.dividendstream.api.auth

import com.dividendstream.api.common.ConflictException
import com.dividendstream.api.common.ForbiddenException
import com.dividendstream.api.common.UnauthorizedException
import com.dividendstream.api.config.JwtProperties
import com.dividendstream.api.config.RegistrationProperties
import com.dividendstream.api.security.JwtService
import com.dividendstream.api.user.UserEntity
import com.dividendstream.api.user.UserRepository
import com.dividendstream.api.user.toProfileResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val jwtProperties: JwtProperties,
    private val clock: Clock,
    private val registrationProperties: RegistrationProperties,
) {

    private val secureRandom = SecureRandom()

    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        requireValidInviteCode(request.inviteCode)

        val email = request.email.trim().lowercase()
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ConflictException("EMAIL_ALREADY_REGISTERED", "That email is already registered.")
        }

        val user = userRepository.save(
            UserEntity(
                name = request.name.trim(),
                email = email,
                passwordHash = passwordEncoder.encode(request.password),
            ),
        )
        return issueSession(user)
    }

    /**
     * Compared with a constant-time equality check. A naive comparison leaks the code one
     * character at a time to anyone able to measure response times.
     */
    private fun requireValidInviteCode(supplied: String?) {
        val expected = registrationProperties.inviteCode.trim()
        if (expected.isEmpty()) return

        val given = supplied?.trim().orEmpty()
        val matches = MessageDigest.isEqual(
            given.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            throw ForbiddenException(
                message = "That invite code is not valid. Ask whoever shared this app for one.",
                code = "INVALID_INVITE_CODE",
            )
        }
    }

    @Transactional
    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmailIgnoreCase(request.email.trim()).orElse(null)

        // Hash even when the user does not exist, so response time does not reveal which
        // emails are registered.
        val passwordMatches = if (user == null) {
            passwordEncoder.matches(request.password, DUMMY_HASH)
            false
        } else {
            passwordEncoder.matches(request.password, user.passwordHash)
        }

        if (user == null || !passwordMatches) {
            throw UnauthorizedException("Incorrect email or password.", "INVALID_CREDENTIALS")
        }
        return issueSession(user)
    }

    /**
     * Exchanges a refresh token for a new session, rotating the refresh token. The presented
     * token is revoked whether or not it was still valid, so a stolen token is single-use.
     */
    @Transactional
    fun refresh(request: RefreshRequest): AuthResponse {
        val now = Instant.now(clock)
        val stored = refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken))
            .orElseThrow { UnauthorizedException("Your session has expired. Please sign in again.", "REFRESH_TOKEN_INVALID") }

        if (!stored.isUsable(now)) {
            throw UnauthorizedException("Your session has expired. Please sign in again.", "REFRESH_TOKEN_INVALID")
        }

        val user = userRepository.findById(stored.userId)
            .orElseThrow { UnauthorizedException("Your session has expired. Please sign in again.", "REFRESH_TOKEN_INVALID") }

        stored.revokedAt = now
        refreshTokenRepository.save(stored)

        return issueSession(user)
    }

    @Transactional
    fun logout(userId: UUID) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now(clock))
    }

    private fun issueSession(user: UserEntity): AuthResponse {
        val accessToken = jwtService.issueAccessToken(user.id, user.email)
        val refreshToken = createRefreshToken(user.id)
        return AuthResponse(
            accessToken = accessToken.value,
            refreshToken = refreshToken,
            accessTokenExpiresAt = accessToken.expiresAt,
            user = user.toProfileResponse(),
        )
    }

    private fun createRefreshToken(userId: UUID): String {
        val raw = ByteArray(REFRESH_TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val now = Instant.now(clock)

        refreshTokenRepository.save(
            RefreshTokenEntity(
                userId = userId,
                tokenHash = hashToken(token),
                expiresAt = now.plus(jwtProperties.refreshTokenTtl),
                createdAt = now,
            ),
        )
        return token
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val REFRESH_TOKEN_BYTES = 32

        /** A real BCrypt hash of a value nobody knows, used purely to equalise timing. */
        const val DUMMY_HASH = "\$2a\$12\$C6UzMDM.H6dfI/f/IKcEe.7ZfQCbSHmDDBmqoTNGmNqM1w0/aQKoK"
    }
}
