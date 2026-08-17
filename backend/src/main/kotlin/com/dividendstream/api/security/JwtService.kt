package com.dividendstream.api.security

import com.dividendstream.api.common.UnauthorizedException
import com.dividendstream.api.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/** An issued access token together with the moment it stops being valid. */
data class IssuedToken(val value: String, val expiresAt: Instant)

@Service
class JwtService(
    private val properties: JwtProperties,
    environment: Environment,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val signingKey: SecretKey = resolveSigningKey(environment)

    fun issueAccessToken(userId: UUID, email: String): IssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val token = Jwts.builder()
            .subject(userId.toString())
            .issuer(properties.issuer)
            .claim("email", email)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact()
        return IssuedToken(token, expiresAt)
    }

    /**
     * Verifies signature, issuer and expiry. Throws [UnauthorizedException] with a code the
     * client can act on -- TOKEN_EXPIRED means "refresh and retry", TOKEN_INVALID means
     * "log the user out".
     */
    fun authenticate(token: String): AuthPrincipal {
        val claims = try {
            Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (ex: ExpiredJwtException) {
            throw UnauthorizedException("Your session has expired. Please sign in again.", "TOKEN_EXPIRED")
        } catch (ex: JwtException) {
            throw UnauthorizedException("Invalid credentials.", "TOKEN_INVALID")
        } catch (ex: IllegalArgumentException) {
            throw UnauthorizedException("Invalid credentials.", "TOKEN_INVALID")
        }

        val userId = runCatching { UUID.fromString(claims.subject) }.getOrNull()
            ?: throw UnauthorizedException("Invalid credentials.", "TOKEN_INVALID")
        return AuthPrincipal(userId, claims["email"] as? String ?: "")
    }

    private fun resolveSigningKey(environment: Environment): SecretKey {
        val secret = properties.secret.trim()
        if (secret.isNotBlank()) {
            require(secret.length >= MIN_SECRET_LENGTH) {
                "dividend-stream.jwt.secret must be at least $MIN_SECRET_LENGTH characters"
            }
            return Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))
        }

        // A missing secret is a hard failure anywhere it could be a real deployment.
        val isProductionLike = environment.activeProfiles.none { it in NON_PRODUCTION_PROFILES }
        check(!isProductionLike) {
            "JWT_SECRET must be set. Refusing to start with a generated key outside local development."
        }

        log.warn(
            "JWT_SECRET is not set - generating an ephemeral signing key. " +
                "All tokens become invalid when this process restarts.",
        )
        return Jwts.SIG.HS256.key().build()
    }

    private companion object {
        const val MIN_SECRET_LENGTH = 32
        val NON_PRODUCTION_PROFILES = setOf("local", "dev", "test")
    }
}
