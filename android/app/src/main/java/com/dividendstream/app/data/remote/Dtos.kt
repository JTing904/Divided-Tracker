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
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    /** Only required when the server is configured to require one. */
    val inviteCode: String? = null,
)

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
    /** Names this purchase so sending it twice buys the shares once. */
    val idempotencyKey: String? = null,
    val symbol: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val manualDividend: ManualDividendRequest? = null,
)

@Serializable
data class ConfirmReceivedRequest(val receivedOn: LocalDate)

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
    /** False means the date is this application's estimate, not the issuer's announcement. */
    val paymentDateConfirmed: Boolean = false,
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

// --- app version -------------------------------------------------------------

/**
 * Nulls mean "the backend has no opinion", which is not the same as "you are up to date".
 * Defaulted so an older client can still read a response from a newer backend.
 */
@Serializable
data class AppVersionDto(
    val service: String = "",
    val commit: String? = null,
    val latestClient: String? = null,
    val minimumClient: String? = null,
    val readyAt: Instant? = null,
    val uptimeSeconds: Long? = null,
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

// --- google sign-in ----------------------------------------------------------

@Serializable
data class GoogleConfigDto(
    val enabled: Boolean = false,
    val desktopEnabled: Boolean = false,
    /** What Android passes to Credential Manager: the Web client ID, not the Android one. */
    val webClientId: String? = null,
    val desktopClientId: String? = null,
)

@Serializable
data class GoogleSignInRequest(val idToken: String, val inviteCode: String? = null)

@Serializable
data class GoogleDesktopSignInRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val inviteCode: String? = null,
)

/**
 * What a platform managed to obtain from Google, before the backend turns it into a session.
 *
 * The two clients cannot produce the same thing. The phone is handed a finished ID token by
 * Credential Manager; the desktop only gets an authorisation code, because redeeming one needs
 * the client secret and an installed binary is no place to keep it.
 */
sealed interface GoogleAuthAttempt {
    data class IdToken(val idToken: String) : GoogleAuthAttempt

    data class AuthorizationCode(
        val code: String,
        val codeVerifier: String,
        val redirectUri: String,
    ) : GoogleAuthAttempt
}

// --- ledger ------------------------------------------------------------------

@Serializable
data class SaveCashFlowRequest(
    /** Names this save, so sending it twice records one flow. */
    val id: String? = null,
    val name: String,
    /** INCOME or EXPENSE. */
    val direction: String,
    val amount: BigDecimal,
    /** DAILY, WEEKLY, MONTHLY or YEARLY. */
    val period: String,
    val category: String? = null,
    val startsOn: LocalDate? = null,
    val endsOn: LocalDate? = null,
)

@Serializable
data class SaveLedgerEntryRequest(
    val id: String? = null,
    val direction: String,
    val amount: BigDecimal,
    val occurredOn: LocalDate? = null,
    val category: String? = null,
    val note: String? = null,
)

@Serializable
data class SaveFundRequest(
    val id: String? = null,
    val name: String,
    val percent: BigDecimal,
    val icon: String? = null,
    val position: Int? = null,
)

@Serializable
data class CashFlowDto(
    val id: String,
    val name: String,
    val direction: String,
    val amount: BigDecimal,
    val period: String,
    val category: String? = null,
    val currency: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate? = null,
    val ratePerSecond: BigDecimal,
    /** Null when this flow is not live at any point in the current month. */
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
    val expectedThisMonth: BigDecimal,
    val accruedThisMonth: BigDecimal,
)

@Serializable
data class LedgerEntryDto(
    val id: String,
    val occurredOn: LocalDate,
    val direction: String,
    val amount: BigDecimal,
    val category: String? = null,
    val note: String? = null,
)

@Serializable
data class FundDto(
    val id: String,
    val name: String,
    val percent: BigDecimal,
    val icon: String? = null,
    val position: Int = 0,
    val ratePerSecond: BigDecimal,
    val plannedThisMonth: BigDecimal,
    val accruedThisMonth: BigDecimal,
    /**
     * What the fund holds, as of `serverTime`. Fills by itself from the share; carries across
     * months rather than resetting.
     */
    val balance: BigDecimal = BigDecimal.ZERO,
    /**
     * The settled part of [balance] -- earlier months, plus deposits, less withdrawals -- which
     * does not move until the month ends. The client adds this month's still-growing share to
     * it each frame, and so arrives at the same total the server would.
     */
    val carriedOver: BigDecimal = BigDecimal.ZERO,
    val earmarkedEarlier: BigDecimal = BigDecimal.ZERO,
    val paidIn: BigDecimal = BigDecimal.ZERO,
    val takenOut: BigDecimal = BigDecimal.ZERO,
    val movements: List<FundMovementDto> = emptyList(),
)

@Serializable
data class FundMovementDto(
    val id: String,
    val fundId: String,
    val occurredOn: LocalDate,
    /** DEPOSIT or WITHDRAWAL. */
    val direction: String,
    val amount: BigDecimal,
    val note: String? = null,
)

@Serializable
data class SaveFundMovementRequest(
    val id: String? = null,
    val direction: String,
    val amount: BigDecimal,
    val occurredOn: LocalDate? = null,
    val note: String? = null,
)

@Serializable
data class LedgerRateDto(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perWeek: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)

@Serializable
data class MonthlyLedgerTotalDto(
    val month: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val net: BigDecimal,
    val entryCount: Int = 0,
)

/**
 * The whole ledger screen in one response.
 *
 * `planned` and `accrued` are projections from the recurring flows the person declared;
 * `actual` is what they actually wrote down. The two are never added together, here or on the
 * screen -- somebody reading a number has to know which of the two it is.
 */
@Serializable
data class LedgerDto(
    val serverTime: Instant,
    val currency: String,
    val month: String,
    val monthStart: Instant,
    val monthEnd: Instant,
    val daysLeftInMonth: Long = 0,

    val netRatePerSecond: BigDecimal,
    val incomeRatePerSecond: BigDecimal,
    val expenseRatePerSecond: BigDecimal,
    val rate: LedgerRateDto,

    val plannedIncome: BigDecimal,
    val plannedExpense: BigDecimal,
    val accruedIncome: BigDecimal,
    val accruedExpense: BigDecimal,
    val netAccrued: BigDecimal,

    val actualIncome: BigDecimal,
    val actualExpense: BigDecimal,
    val actualNet: BigDecimal,

    val funds: List<FundDto> = emptyList(),
    val allocatedPercent: BigDecimal,
    val unallocatedPercent: BigDecimal,
    val totalFundBalance: BigDecimal = BigDecimal.ZERO,

    val flows: List<CashFlowDto> = emptyList(),
    val entries: List<LedgerEntryDto> = emptyList(),
    val months: List<MonthlyLedgerTotalDto> = emptyList(),
)

/**
 * The parameters the shared accumulation calculator ticks from -- the same four the dividend
 * counter uses, which is what keeps the client's figure and the server's in step.
 *
 * Null for a flow that is not live in the current month: it has nothing to contribute, and a
 * stream with no window would count from the epoch.
 */
fun CashFlowDto.toAccumulationStream(): AccumulationStream? {
    val start = windowStart ?: return null
    val end = windowEnd ?: return null
    return AccumulationStream(
        expectedAmount = expectedThisMonth,
        ratePerSecond = ratePerSecond,
        start = start,
        end = end,
    )
}
