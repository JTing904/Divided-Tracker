package com.dividendstream.api.stock

import com.dividendstream.api.common.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * A tradable instrument, cached locally from whichever [com.dividendstream.api.marketdata.StockDataProvider]
 * is configured. Prices are refreshed on a schedule, never per request.
 */
@Entity
@Table(name = "stocks")
class StockEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "symbol", nullable = false, length = 32)
    var symbol: String = "",

    @Column(name = "company_name", nullable = false, length = 200)
    var companyName: String = "",

    @Column(name = "exchange", nullable = false, length = 32)
    var exchange: String = "",

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "MYR",

    @Column(name = "sector", length = 100)
    var sector: String? = null,

    @Column(name = "last_price", precision = 19, scale = 4)
    var lastPrice: BigDecimal? = null,

    @Column(name = "price_updated_at")
    var priceUpdatedAt: Instant? = null,
) : AuditableEntity()
