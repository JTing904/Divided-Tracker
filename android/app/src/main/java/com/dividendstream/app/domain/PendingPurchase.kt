@file:UseSerializers(BigDecimalSerializer::class, InstantSerializer::class)

package com.dividendstream.app.domain

import com.dividendstream.app.core.BigDecimalSerializer
import com.dividendstream.app.core.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant

/**
 * A purchase the user has entered but the server has not yet accepted.
 *
 * Written to the device before anything is sent, so a purchase survives the app being closed
 * while the backend is still waking. Losing one silently is not an option a financial record
 * can offer: the person typed it, pressed save, and is entitled to assume it happened.
 *
 * [idempotencyKey] is generated once, when the purchase is queued, and never changes. That is
 * what makes resending safe -- the server recognises the repeat and applies the purchase once,
 * however many times a reply goes missing on the way back.
 */
@Serializable
data class PendingPurchase(
    val idempotencyKey: String,
    val symbol: String,
    /** Kept so the queue can name the stock without the server being reachable. */
    val companyName: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val queuedAt: Instant,
    /**
     * Set when the server refused in a way retrying cannot fix -- an unknown symbol, a
     * malformed figure. Such a purchase stops being retried and waits to be seen, because a
     * queue that silently keeps failing forever is worse than one that says so.
     */
    val failure: String? = null,
) {
    val isBlocked: Boolean get() = failure != null
}
