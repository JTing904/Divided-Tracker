package com.dividendstream.api.stock

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Search-result shape: enough to choose a stock, nothing more. */
data class StockSummaryResponse(
    val id: UUID?,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String?,
    val lastPrice: BigDecimal?,
)

data class StockDetailResponse(
    val id: UUID,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String?,
    val lastPrice: BigDecimal?,
    val priceUpdatedAt: Instant?,
    /** Most recently declared dividend per share, if any is known. */
    val dividendPerShare: BigDecimal?,
    /** Annualised dividend / price, as a percentage. Null when price is unknown. */
    val dividendYieldPercent: BigDecimal?,
    val dividendFrequency: String?,
    val exDate: LocalDate?,
    val recordDate: LocalDate?,
    val nextPaymentDate: LocalDate?,
)
