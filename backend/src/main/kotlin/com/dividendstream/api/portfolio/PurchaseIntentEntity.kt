package com.dividendstream.api.portfolio

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A purchase the client has already had carried out, remembered by the client's own name for it.
 *
 * The record exists so the same intent can arrive twice and be applied once. A queued purchase
 * is retried until a reply arrives, and a reply can be lost after the work is done -- so "did
 * this already happen?" has to be answerable from the request itself rather than from whether
 * the client heard back.
 */
@Entity
@Table(name = "purchase_intents")
class PurchaseIntentEntity(

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    var idempotencyKey: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    /** The holding the purchase went into, whether it was created or enlarged. */
    @Column(name = "holding_id", nullable = false, updatable = false)
    var holdingId: UUID = UUID.randomUUID(),

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
