package com.dividendstream.api.dividend

import java.math.BigDecimal
import java.time.Instant
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PastOrPresent
import java.time.LocalDate
import java.util.UUID

/** A per-second rate restated over longer horizons, for the dashboard. */
data class RateBreakdownResponse(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)

/**
 * One accumulating dividend.
 *
 * The client re-derives [accruedAmount] locally between refreshes using [ratePerSecond],
 * [accumulationStart] and [accumulationEnd] -- the same inputs the server used -- so the
 * counter keeps moving offline and lands on the same value the server would report.
 */
data class LiveStreamResponse(
    val transactionId: UUID,
    val stockId: UUID,
    val symbol: String,
    val companyName: String,
    val shares: BigDecimal,
    val dividendPerShare: BigDecimal,
    val expectedAmount: BigDecimal,
    val accruedAmount: BigDecimal,
    val ratePerSecond: BigDecimal,
    val accumulationStart: Instant,
    val accumulationEnd: Instant,
    val progress: BigDecimal,
    val status: DividendStatus,
    val paymentDate: LocalDate,
    val currency: String,
)

data class LiveDividendResponse(
    /**
     * Authoritative time. The client stores `serverTime - deviceTime` and ticks against the
     * corrected clock, so a device with a wrong system time still shows the right figure.
     */
    val serverTime: Instant,
    val currency: String,
    /** Sum of what the in-flight cycles are expected to pay. An estimate. */
    val totalExpected: BigDecimal,
    /** Estimated accumulation so far. Not money received. */
    val totalAccrued: BigDecimal,
    /** Actually settled income, summed from `paid_amount`. */
    val totalReceived: BigDecimal,
    val rate: RateBreakdownResponse,
    val activeStockCount: Int,
    val nextPayment: DividendResponse?,
    val streams: List<LiveStreamResponse>,
)

data class DividendResponse(
    val id: UUID,
    val stockId: UUID,
    val symbol: String,
    val companyName: String,
    val shares: BigDecimal,
    val dividendPerShare: BigDecimal,
    val expectedAmount: BigDecimal,
    val paidAmount: BigDecimal?,
    val accruedAmount: BigDecimal,
    val ratePerSecond: BigDecimal,
    val currency: String,
    val accumulationStart: Instant,
    val accumulationEnd: Instant,
    val progress: BigDecimal,
    val status: DividendStatus,
    val frequency: DividendFrequency?,
    val exDate: LocalDate?,
    val recordDate: LocalDate?,
    val paymentDate: LocalDate,
    val paidAt: Instant?,
)

data class UpcomingDividendsResponse(
    val serverTime: Instant,
    val currency: String,
    val totalExpected: BigDecimal,
    val items: List<DividendResponse>,
)

/** Settled dividends for one calendar month, newest first. */
data class MonthlyDividendGroup(
    /** ISO year-month, e.g. `2026-08`. Formatting is the client's job. */
    val month: String,
    val total: BigDecimal,
    val items: List<DividendResponse>,
)

data class StockDividendTotal(
    val stockId: UUID,
    val symbol: String,
    val companyName: String,
    val total: BigDecimal,
)

data class DividendHistoryResponse(
    val currency: String,
    val totalReceived: BigDecimal,
    val receivedThisYear: BigDecimal,
    val receivedThisMonth: BigDecimal,
    val months: List<MonthlyDividendGroup>,
    val byStock: List<StockDividendTotal>,
)

/** The one fact about a dividend that only the person who received it can supply. */
data class ConfirmReceivedRequest(
    @field:NotNull(message = "The date you received it is required")
    @field:PastOrPresent(message = "That date is in the future")
    val receivedOn: LocalDate,
)
