package com.dividendstream.api.ledger

import com.dividendstream.api.common.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * A recurring amount a person declares once: a salary, an allowance, rent, a subscription.
 *
 * This is a *projection*, not a record of anything that happened. It is the only thing the
 * per-second counter is derived from, and it is deliberately kept in a different table from
 * [LedgerEntryEntity] so that the two can never be added together by accident.
 */
@Entity
@Table(name = "cash_flows")
class CashFlowEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false, length = 80)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: FlowDirection = FlowDirection.INCOME,

    /** Always positive. [direction] carries the sign. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 16)
    var period: CashFlowPeriod = CashFlowPeriod.MONTHLY,

    @Column(name = "category", length = 40)
    var category: String? = null,

    /**
     * Which day of its period this pays on. Null means the day the period ends.
     *
     * WEEKLY reads it as an ISO day of week (Monday is 1); MONTHLY as a day of the month,
     * clamped where the month is shorter. YEARLY reads it together with [arrivesMonth].
     * DAILY ignores it: a day cannot pay on some other day.
     */
    @Column(name = "arrives_on")
    var arrivesOn: Short? = null,

    /**
     * Which month of the year a YEARLY flow pays in, with [arrivesOn] as the day within it.
     *
     * Only a year needs this. Every shorter period is contained by a month and names its own
     * day, so a month would tell them nothing they do not already know.
     */
    @Column(name = "arrives_month")
    var arrivesMonth: Short? = null,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "MYR",

    /** The first day this applies. A job begun mid-month accrues only from that day. */
    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate = LocalDate.EPOCH,

    /** The last day this applies, inclusive. Null means it is still running. */
    @Column(name = "ends_on")
    var endsOn: LocalDate? = null,
) : AuditableEntity()

/**
 * One thing that actually happened on one day.
 *
 * A fact, not an estimate. These are what the month's totals are settled against, and they
 * never feed the counter -- a person has to be able to tell which of the two numbers on the
 * screen is a projection and which is what they really spent.
 */
@Entity
@Table(name = "ledger_entries")
class LedgerEntryEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "occurred_on", nullable = false)
    var occurredOn: LocalDate = LocalDate.EPOCH,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: FlowDirection = FlowDirection.EXPENSE,

    /** Always positive. [direction] carries the sign. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "category", length = 40)
    var category: String? = null,

    @Column(name = "note", length = 200)
    var note: String? = null,
) : AuditableEntity()

/**
 * A destination for money left over: an emergency fund, a trip, an investment pot.
 *
 * Holds a *share*, not an amount. "30% of whatever is left" keeps filling correctly when a
 * salary changes or a month runs short, where a fixed figure would quietly become a lie. It is
 * also what lets a fund fill in real time next to the counter rather than once a month.
 */
@Entity
@Table(name = "funds")
class FundEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false, length = 80)
    var name: String = "",

    /** Percent of the surplus, 0 exclusive to 100 inclusive. One person's funds may not exceed 100. */
    @Column(name = "percent", nullable = false, precision = 5, scale = 2)
    var percent: BigDecimal = BigDecimal.ZERO,

    /** Names one of the client's built-in icons. Never a path or a URL. */
    @Column(name = "icon", length = 40)
    var icon: String? = null,

    @Column(name = "position", nullable = false)
    var position: Int = 0,
) : AuditableEntity()

/** Which way money moved between a person and one of their funds. */
enum class FundMovementDirection { DEPOSIT, WITHDRAWAL }

/**
 * Who made a movement.
 *
 * [HAND] is a person moving their own money. [MONTHLY_SHARE] is the app banking a finished
 * month's percentage, which is the one thing it does on their behalf -- and it is told apart
 * so that "put in by hand" keeps meaning what it says.
 */
enum class FundMovementSource { HAND, MONTHLY_SHARE }

/**
 * Money actually put into, or taken out of, a fund.
 *
 * A fact, like [LedgerEntryEntity] and unlike [FundEntity]'s share of the surplus. Nothing
 * here is ever created by the app on the person's behalf: a deposit means they moved the
 * money, and the difference between "the plan says RM412 should go to the emergency fund" and
 * "RM412 is in the emergency fund" is the whole point of keeping this table separate.
 */
@Entity
@Table(name = "fund_movements")
class FundMovementEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID = UUID.randomUUID(),

    @Column(name = "fund_id", nullable = false, updatable = false)
    var fundId: UUID = UUID.randomUUID(),

    @Column(name = "occurred_on", nullable = false)
    var occurredOn: LocalDate = LocalDate.EPOCH,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: FundMovementDirection = FundMovementDirection.DEPOSIT,

    /** Always positive. [direction] carries the sign. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    var amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "note", length = 200)
    var note: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    var source: FundMovementSource = FundMovementSource.HAND,

    /**
     * Which month a [FundMovementSource.MONTHLY_SHARE] row banks, as `2026-08`. Null otherwise.
     *
     * A unique index over (fund, this) is what makes settling on read safe: two requests
     * arriving together cannot bank August twice, because the second insert loses.
     */
    @Column(name = "settled_month", length = 7)
    var settledMonth: String? = null,
) : AuditableEntity()
