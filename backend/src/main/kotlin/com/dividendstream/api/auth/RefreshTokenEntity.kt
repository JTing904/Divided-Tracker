package com.dividendstream.api.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A refresh token, stored only as a SHA-256 hash. The raw token exists solely in the
 * response to the client, so a database leak yields nothing that can be replayed.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    var tokenHash: String = "",

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun isUsable(now: Instant): Boolean = revokedAt == null && expiresAt.isAfter(now)
}
