package com.dividendstream.api.dividend

import com.dividendstream.api.common.AuditableEntity
import com.dividendstream.api.stock.StockEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One user's entitlement to one dividend cycle, and the source of truth for the live
 * counter.
 *
 * The four accumulation parameters -- [expectedAmount], [accumulationStart],
 * [accumulationEnd], [ratePerSecond] -- are written when the cycle is created or the
 * holding changes, and then left alone. No row is ever updated on a per-second tick; the
 * displayed value is recomputed from these parameters plus the current time.
 */
@Entity
@Table(name = "dividend_transactions")
class DividendTransactionEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false, updatable = false)
    var stock: StockEntity = StockEntity(),

    /** Null once the position is closed; the entitlement survives the holding. */
    @Column(name = "holding_id")
    var holdingId: UUID? = null,

    @Column(name = "dividend_id", nullable = false, updatable = false)
    var dividendId: UUID = UUID.randomUUID(),

    /** Share count at the time the entitlement was computed. */
    @Column(name = "shares", nullable = false, precision = 19, scale = 4)
    var shares: BigDecimal = BigDecimal.ZERO,

    @Column(name = "dividend_per_share", nullable = false, precision = 19, scale = 8)
    var dividendPerShare: BigDecimal = BigDecimal.ZERO,

    /** An estimate: shares x dividendPerShare. Never presented as received income. */
    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 2)
    var expectedAmount: BigDecimal = BigDecimal.ZERO,

    /** Set only on settlement. This -- not the estimate -- is real income. */
    @Column(name = "paid_amount", precision = 19, scale = 2)
    var paidAmount: BigDecimal? = null,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "MYR",

    @Column(name = "accumulation_start", nullable = false)
    var accumulationStart: Instant = Instant.EPOCH,

    @Column(name = "accumulation_end", nullable = false)
    var accumulationEnd: Instant = Instant.EPOCH,

    @Column(name = "rate_per_second", nullable = false, precision = 24, scale = 12)
    var ratePerSecond: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: DividendStatus = DividendStatus.UPCOMING,

    @Column(name = "payment_date", nullable = false)
    var paymentDate: LocalDate = LocalDate.EPOCH,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,
) : AuditableEntity()
