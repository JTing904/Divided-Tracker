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
import java.time.LocalDate
import java.util.UUID

/**
 * How often a stock declares. [accumulationDays] is the period the dividend is *earned*
 * over, and it defines the accumulation window: a semi-annual RM320 dividend accrues across
 * ~182 days, giving roughly RM0.0000203 per second, rather than appearing all at once in
 * the few weeks between ex-date and payment.
 */
enum class DividendFrequency(val accumulationDays: Long) {
    MONTHLY(30),
    QUARTERLY(91),
    SEMI_ANNUAL(182),
    ANNUAL(365),

    /** An unscheduled interim payout; treated as half-yearly for pacing purposes. */
    INTERIM(182),

    /** A one-off special dividend; paced over a quarter. */
    SPECIAL(91),
}

/**
 * One declared dividend cycle for a stock. This is *market* data and is shared by every
 * user holding that stock; per-user amounts live in [DividendTransactionEntity].
 */
@Entity
@Table(name = "dividends")
class DividendEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false, updatable = false)
    var stock: StockEntity = StockEntity(),

    @Column(name = "dividend_per_share", nullable = false, precision = 19, scale = 8)
    var dividendPerShare: BigDecimal = BigDecimal.ZERO,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "MYR",

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    var frequency: DividendFrequency = DividendFrequency.INTERIM,

    /** Buy before this date to be entitled; also when accumulation starts. */
    @Column(name = "ex_date", nullable = false)
    var exDate: LocalDate = LocalDate.EPOCH,

    @Column(name = "record_date")
    var recordDate: LocalDate? = null,

    /** Accumulation ends here and the amount becomes payable. */
    @Column(name = "payment_date", nullable = false)
    var paymentDate: LocalDate = LocalDate.EPOCH,

    /** Which provider supplied this row, e.g. `mock`. */
    @Column(name = "source", nullable = false, length = 32)
    var source: String = "mock",
) : AuditableEntity()
