package com.dividendstream.api.portfolio

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface PurchaseIntentRepository : JpaRepository<PurchaseIntentEntity, UUID> {

    /** Scoped to the user, so one person's key can never resolve to another's holding. */
    fun findByIdempotencyKeyAndUserId(idempotencyKey: UUID, userId: UUID): Optional<PurchaseIntentEntity>
}
