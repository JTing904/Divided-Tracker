package com.dividendstream.api.ledger

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --- requests ----------------------------------------------------------------

data class SaveCashFlowRequest(
    /**
     * The client's own name for this flow, so sending it twice records it once.
     *
     * Optional. Supplying it is what makes a retry -- or a double tap on Save -- safe, and
     * costs nothing when it is not needed.
     */
    val id: UUID? = null,

    @field:NotBlank(message = "Give this a name")
    @field:Size(max = 80, message = "That name is too long")
    val name: String,

    @field:NotNull(message = "Say whether this is money in or money out")
    val direction: FlowDirection,

    @field:NotNull(message = "An amount is required")
    @field:DecimalMin(value = "0.01", message = "Enter an amount above zero")
    @field:DecimalMax(value = "999999999.99", message = "That is more than we can track")
    @field:Digits(integer = 9, fraction = 2, message = "An amount may have at most 2 decimal places")
    val amount: BigDecimal,

    @field:NotNull(message = "How often does this repeat?")
    val period: CashFlowPeriod,

    @field:Size(max = 40, message = "That category name is too long")
    val category: String? = null,

    /** Defaults to today on the server, so a client need not send one. */
    val startsOn: LocalDate? = null,

    /** Inclusive. Null means it is still running. */
    val endsOn: LocalDate? = null,
)

data class SaveLedgerEntryRequest(
    /** See [SaveCashFlowRequest.id]: this is what stops a double tap recording twice. */
    val id: UUID? = null,

    @field:NotNull(message = "Say whether this is money in or money out")
    val direction: FlowDirection,

    @field:NotNull(message = "An amount is required")
    @field:DecimalMin(value = "0.01", message = "Enter an amount above zero")
    @field:DecimalMax(value = "999999999.99", message = "That is more than we can track")
    @field:Digits(integer = 9, fraction = 2, message = "An amount may have at most 2 decimal places")
    val amount: BigDecimal,

    /** Defaults to today on the server. */
    val occurredOn: LocalDate? = null,

    @field:Size(max = 40, message = "That category name is too long")
    val category: String? = null,

    @field:Size(max = 200, message = "That note is too long")
    val note: String? = null,
)

data class SaveFundRequest(
    /** See [SaveCashFlowRequest.id]: this is what stops a double tap creating two funds. */
    val id: UUID? = null,

    @field:NotBlank(message = "Give this fund a name")
    @field:Size(max = 80, message = "That name is too long")
    val name: String,

    @field:NotNull(message = "What share of what is left should go here?")
    @field:DecimalMin(value = "0.01", message = "Enter a share above zero")
    @field:DecimalMax(value = "100.00", message = "A single fund cannot take more than 100%")
    @field:Digits(integer = 3, fraction = 2, message = "A share may have at most 2 decimal places")
    val percent: BigDecimal,

    /** Names one of the client's built-in icons. */
    @field:Size(max = 40)
    val icon: String? = null,

    val position: Int? = null,
)

data class SaveFundMovementRequest(
    /** See [SaveCashFlowRequest.id]: this is what stops a double tap moving the money twice. */
    val id: UUID? = null,

    @field:NotNull(message = "Say whether money is going in or coming out")
    val direction: FundMovementDirection,

    @field:NotNull(message = "An amount is required")
    @field:DecimalMin(value = "0.01", message = "Enter an amount above zero")
    @field:DecimalMax(value = "999999999.99", message = "That is more than we can track")
    @field:Digits(integer = 9, fraction = 2, message = "An amount may have at most 2 decimal places")
    val amount: BigDecimal,

    /** Defaults to today on the server. */
    val occurredOn: LocalDate? = null,

    @field:Size(max = 200, message = "That note is too long")
    val note: String? = null,
)

// --- responses ---------------------------------------------------------------

/**
 * One recurring figure, restated as everything the client needs to tick it locally.
 *
 * [ratePerSecond], [windowStart], [windowEnd] and [expectedThisMonth] are exactly the four
 * parameters the client's shared accumulation calculator takes -- the same one the dividend
 * counter uses. That is deliberate: one formula, used by both, cannot drift from the server's.
 */
data class CashFlowResponse(
    val id: UUID,
    val name: String,
    val direction: FlowDirection,
    val amount: BigDecimal,
    val period: CashFlowPeriod,
    val category: String?,
    val currency: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val ratePerSecond: BigDecimal,
    /** Null when the flow is not live at any point in the current month. */
    val windowStart: Instant?,
    val windowEnd: Instant?,
    /** What this flow amounts to across the part of this month it is live for. */
    val expectedThisMonth: BigDecimal,
    val accruedThisMonth: BigDecimal,
)

data class LedgerEntryResponse(
    val id: UUID,
    val occurredOn: LocalDate,
    val direction: FlowDirection,
    val amount: BigDecimal,
    val category: String?,
    val note: String?,
)

/**
 * One destination for the money left over, and what it is currently receiving.
 *
 * The figures are a share of the *projected* surplus -- income minus outgoings from the
 * declared flows -- because that is the number that moves per second and can be watched. When
 * there is no surplus, every fund receives nothing: a deficit is not divided up, and showing a
 * fund filling from money that is not there would be the one thing this screen must not do.
 */
data class FundResponse(
    val id: UUID,
    val name: String,
    val percent: BigDecimal,
    val icon: String?,
    val position: Int,
    /** This fund's share of the per-second surplus. Zero when there is no surplus. */
    val ratePerSecond: BigDecimal,
    /** Its share of the whole month's projected surplus. */
    val plannedThisMonth: BigDecimal,
    /** Its share of the surplus accrued so far, at `serverTime`. */
    val accruedThisMonth: BigDecimal,

    /**
     * What the fund holds: everything the plan has put aside since it was created, plus
     * anything paid in by hand, less everything taken out.
     *
     * This is the figure a person means by "how much is in my emergency fund". It fills by
     * itself -- setting a share of 50% is an instruction, not a reminder to do it manually --
     * and it carries across months rather than resetting on the 1st.
     */
    val balance: BigDecimal,

    /**
     * The part of [balance] that is settled and does not move until the month ends: earlier
     * months, plus deposits, less withdrawals.
     *
     * Sent separately so the client can add this month's still-growing share to it once per
     * frame and arrive at the same total the server would, rather than waiting for a refresh
     * to see the fund move.
     */
    val carriedOver: BigDecimal,

    /** What earlier months alone contributed. A projection; see [carriedOver]. */
    val earmarkedEarlier: BigDecimal,

    /** Money the person put in themselves, beyond the plan. A fact. */
    val paidIn: BigDecimal,

    /** Money the person spent out of the fund. A fact. */
    val takenOut: BigDecimal,

    val movements: List<FundMovementResponse>,
)

data class FundMovementResponse(
    val id: UUID,
    val fundId: UUID,
    val occurredOn: LocalDate,
    val direction: FundMovementDirection,
    val amount: BigDecimal,
    val note: String?,
)

/** A per-second figure restated over horizons a person thinks in, on the real calendar. */
data class LedgerRateResponse(
    val perSecond: BigDecimal,
    val perMinute: BigDecimal,
    val perHour: BigDecimal,
    val perDay: BigDecimal,
    val perWeek: BigDecimal,
    val perMonth: BigDecimal,
    val perYear: BigDecimal,
)

/** Recorded totals for one past month. Facts only -- no projection is folded in. */
data class MonthlyLedgerTotal(
    /** `2026-08`. */
    val month: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val net: BigDecimal,
    val entryCount: Int,
)

/**
 * Everything the ledger screen needs, in one response.
 *
 * One round trip rather than four, because the server sleeps between uses and each extra call
 * is another wait the person watches.
 */
data class LedgerResponse(
    val serverTime: Instant,
    val currency: String,

    /** Which stretch of time the figures below cover: today, or this month. */
    val period: LedgerPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    /** `2026-08-29` for a day, `2026-08` for a month. */
    val periodLabel: String,

    /**
     * This month, whichever period is being shown.
     *
     * The funds are always answered from it: a share is a share of a month, and switching the
     * screen to today must not make a fund look as though it shrank.
     */
    val month: String,
    val monthStart: Instant,
    val monthEnd: Instant,
    val daysLeftInMonth: Long,

    /** Income minus expense. Negative when the outgoings win, and shown as such. */
    val netRatePerSecond: BigDecimal,
    val incomeRatePerSecond: BigDecimal,
    val expenseRatePerSecond: BigDecimal,
    /** The net rate restated per minute, hour, day, week, month and year. */
    val rate: LedgerRateResponse,

    /** Projected: what the declared flows come to over the whole month. */
    val plannedIncome: BigDecimal,
    val plannedExpense: BigDecimal,
    /** Projected: what they have come to so far, at [serverTime]. */
    val accruedIncome: BigDecimal,
    val accruedExpense: BigDecimal,
    val netAccrued: BigDecimal,

    /**
     * Recorded: what the entries in this period add up to. Already counted in [netAccrued] --
     * writing down a RM12 lunch takes RM12 off what is left, which is what writing it down is
     * for. Reported separately so the screen can show how much of the total came from records
     * rather than from the plan.
     */
    val recordedNet: BigDecimal,
    val actualIncome: BigDecimal,
    val actualExpense: BigDecimal,
    val actualNet: BigDecimal,

    /**
     * Everything left over across every month that has already finished -- the settled part of
     * "how much have I kept", which does not move until this month ends.
     */
    val keptBeforeThisMonth: BigDecimal,

    /**
     * What this *month* has left over, whichever period the screen is showing.
     *
     * Sent so a client on the day view can still add the month to [keptBeforeThisMonth] and
     * arrive at the lifetime figure, rather than showing a total that changes when somebody
     * switches to today.
     */
    val monthNetAccrued: BigDecimal,

    /** [keptBeforeThisMonth] plus [monthNetAccrued], at `serverTime`. */
    val keptSoFar: BigDecimal,

    /**
     * What is left after outgoings, and how it is being divided.
     *
     * [unallocatedPercent] is the share not yet claimed by any fund -- shown rather than
     * hidden, because "70% allocated" is a fact about a budget the person should be able to
     * see, and silently treating the remainder as spare is how a plan stops being a plan.
     */
    val funds: List<FundResponse>,
    val allocatedPercent: BigDecimal,
    val unallocatedPercent: BigDecimal,
    /** Everything sitting in the funds, across all of them. A fact, like each fund's balance. */
    val totalFundBalance: BigDecimal,

    val flows: List<CashFlowResponse>,
    val entries: List<LedgerEntryResponse>,
    val months: List<MonthlyLedgerTotal>,
)
