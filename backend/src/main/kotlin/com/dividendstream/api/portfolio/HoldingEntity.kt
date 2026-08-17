package com.dividendstream.api.portfolio

import com.dividendstream.api.common.AuditableEntity
import com.dividendstream.api.stock.StockEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/** A user's position in one stock. Unique per (user, stock). */
@Entity
@Table(name = "holdings")
class HoldingEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false, updatable = false)
    var stock: StockEntity = StockEntity(),

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    var quantity: BigDecimal = BigDecimal.ZERO,

    @Column(name = "average_price", nullable = false, precision = 19, scale = 4)
    var averagePrice: BigDecimal = BigDecimal.ZERO,
) : AuditableEntity()
