@file:UseSerializers(BigDecimalSerializer::class, InstantSerializer::class, LocalDateSerializer::class)

package com.dividendstream.app.data.remote

import com.dividendstream.app.core.BigDecimalSerializer
import com.dividendstream.app.core.InstantSerializer
import com.dividendstream.app.core.LocalDateSerializer
import com.dividendstream.app.domain.AccumulationStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// --- errors ------------------------------------------------------------------

@Serializable
data class ApiErrorDto(
    val code: String = "INTERNAL_ERROR",
    val message: String = "Something went wrong. Please try again.",
    val fieldErrors: Map<String, String>? = null,
)

// --- auth --------------------------------------------------------------------

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class UserProfileDto(
    val id: String,
    val name: String,
    val email: String,
    val baseCurrency: String,
    val createdAt: Instant,
)

@Serializable
data class UpdateProfileRequest(val name: String, val baseCurrency: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Instant,
    val user: UserProfileDto,
)

// --- stocks ------------------------------------------------------------------

@Serializable
data class StockSummaryDto(
    val id: String? = null,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String? = null,
    val lastPrice: BigDecimal? = null,
)

@Serializable
data class StockDetailDto(
    val id: String,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String? = null,
    val lastPrice: BigDecimal? = null,
    val priceUpdatedAt: Instant? = null,
    val dividendPerShare: BigDecimal? = null,
    val dividendYieldPercent: BigDecimal? = null,
    val dividendFrequency: String? = null,
    val exDate: LocalDate? = null,
    val recordDate: LocalDate? = null,
    val nextPaymentDate: LocalDate? = null,
)

// --- portfolio ---------------------------------------------------------------

@Serializable
data class ManualDividendRequest(
    val dividendPerShare: BigDecimal,
    val frequency: String,
    val exDate: LocalDate,
    val recordDate: LocalDate? = null,
    val paymentDate: LocalDate,
)

@Serializable
data class CreateHoldingRequest(
    val symbol: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val manualDividend: ManualDividendRequest? = null,
)

@Serializable
data class UpdateHoldingRequest(val quantity: BigDecimal, val averagePrice: BigDecimal)

@Serializable
data class HoldingDto(
    val id: String,
    val stockId: String,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String? = null,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal? = null,
    val costBasis: BigDecimal,
    val marketValue: BigDecimal? = null,
    val dividendPerShare: BigDecimal? = null,
    val dividendYieldPercent: BigDecimal? = null,
    val expectedDividend: BigDecimal,
    val nextPaymentDate: LocalDate? = null,
)

@Serializable
data class PortfolioDto(
    val holdings: List<HoldingDto> = emptyList(),
    val totalCostBasis: BigDecimal,
    val totalMarketValue: BigDecimal,
    val totalExpectedDividend: BigDecimal,
    val currency: String,
)

// --- dividends ---------------------------------------------------------------

@Serializable
data class RateBreakdownDto(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)

@Serializable
data class LiveStreamDto(
    val transactionId: String,
    val stockId: String,
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
    val status: String,
    val paymentDate: LocalDate,
    val currency: String,
)

@Serializable
data class DividendDto(
    val id: String,
    val stockId: String,
    val symbol: String,
    val companyName: String,
    val shares: BigDecimal,
    val dividendPerShare: BigDecimal,
    val expectedAmount: BigDecimal,
    val paidAmount: BigDecimal? = null,
    val accruedAmount: BigDecimal,
    val ratePerSecond: BigDecimal,
    val currency: String,
    val accumulationStart: Instant,
    val accumulationEnd: Instant,
    val progress: BigDecimal,
    val status: String,
    val frequency: String? = null,
    val exDate: LocalDate? = null,
    val recordDate: LocalDate? = null,
    val paymentDate: LocalDate,
    val paidAt: Instant? = null,
)

@Serializable
data class LiveDividendDto(
    val serverTime: Instant,
    val currency: String,
    val totalExpected: BigDecimal,
    val totalAccrued: BigDecimal,
    val totalReceived: BigDecimal,
    val rate: RateBreakdownDto,
    val activeStockCount: Int,
    val nextPayment: DividendDto? = null,
    val streams: List<LiveStreamDto> = emptyList(),
)

@Serializable
data class UpcomingDividendsDto(
    val serverTime: Instant,
    val currency: String,
    val totalExpected: BigDecimal,
    val items: List<DividendDto> = emptyList(),
)

@Serializable
data class MonthlyDividendGroupDto(
    val month: String,
    val total: BigDecimal,
    val items: List<DividendDto> = emptyList(),
)

@Serializable
data class StockDividendTotalDto(
    val stockId: String,
    val symbol: String,
    val companyName: String,
    val total: BigDecimal,
)

@Serializable
data class DividendHistoryDto(
    val currency: String,
    val totalReceived: BigDecimal,
    val receivedThisYear: BigDecimal,
    val receivedThisMonth: BigDecimal,
    val months: List<MonthlyDividendGroupDto> = emptyList(),
    val byStock: List<StockDividendTotalDto> = emptyList(),
)

// --- mapping into the calculator's input -------------------------------------

fun LiveStreamDto.toAccumulationStream() = AccumulationStream(
    expectedAmount = expectedAmount,
    ratePerSecond = ratePerSecond,
    start = accumulationStart,
    end = accumulationEnd,
)

fun DividendDto.toAccumulationStream() = AccumulationStream(
    expectedAmount = expectedAmount,
    ratePerSecond = ratePerSecond,
    start = accumulationStart,
    end = accumulationEnd,
)
