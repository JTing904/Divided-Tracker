package com.dividendstream.app.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * What is actually stored, as opposed to what is worked out from it.
 *
 * The difference matters more here than it did with a server in the middle. These are the
 * facts a person entered -- a wage, a lunch, a fund's share -- and they are the only things
 * that are written down. Every figure on the ledger screen is derived from them on the device,
 * every time, by [LedgerCalculator].
 *
 * Money is a [BigDecimal] in memory and a string on the way to Firestore, which has no decimal
 * type of its own. Storing RM0.29 as a Firestore number would store it as a double, and a
 * ledger that keeps money in doubles is one that eventually disagrees with itself by a cent
 * and cannot explain why.
 */
data class StoredFlow(
    val id: String,
    val name: String,
    /** INCOME or EXPENSE. */
    val direction: String,
    val amount: BigDecimal,
    /** DAILY, WEEKLY, MONTHLY or YEARLY. */
    val period: String,
    val category: String? = null,
    val currency: String = "MYR",
    /** Which day of its period this pays on. Null means the day the period ends. */
    val arrivesOn: Int? = null,
    /** Which month a yearly flow pays in, with [arrivesOn] as the day inside it. */
    val arrivesMonth: Int? = null,
    val startsOn: LocalDate,
    /** Inclusive. Null means it is still running. */
    val endsOn: LocalDate? = null,
) {
    val flowDirection: FlowDirection get() = FlowDirection.valueOf(direction)
    val flowPeriod: CashFlowPeriod get() = CashFlowPeriod.valueOf(period)
}

/** One thing that happened on one day. A fact, never an estimate. */
data class StoredEntry(
    val id: String,
    val occurredOn: LocalDate,
    val direction: String,
    val amount: BigDecimal,
    val category: String? = null,
    val note: String? = null,
)

data class StoredFund(
    val id: String,
    val name: String,
    val percent: BigDecimal,
    val icon: String? = null,
    val position: Int = 0,
    /** Settlement walks from the month a fund was made in, so this is load-bearing. */
    val createdAt: Instant,
)

data class StoredMovement(
    val id: String,
    val fundId: String,
    val occurredOn: LocalDate,
    /** DEPOSIT or WITHDRAWAL. */
    val direction: String,
    val amount: BigDecimal,
    val note: String? = null,
    /** HAND, or MONTHLY_SHARE for a month the app banked on the person's behalf. */
    val source: String = HAND,
    /** The month a MONTHLY_SHARE row banks, as `2026-08`. Null for anything done by hand. */
    val settledMonth: String? = null,
) {
    companion object {
        const val HAND = "HAND"
        const val MONTHLY_SHARE = "MONTHLY_SHARE"
        const val DEPOSIT = "DEPOSIT"
        const val WITHDRAWAL = "WITHDRAWAL"

        /**
         * The id a settlement row is written under, worked out rather than generated.
         *
         * On the server a partial unique index on `(fund_id, settled_month)` stopped a month
         * being banked twice. Firestore has no such index, and it does not need one: writing
         * to a document id derived from the same two values means the second attempt lands on
         * the first one instead of beside it. Same guarantee, no index.
         */
        fun settlementId(fundId: String, month: String): String = "$fundId--$month"
    }
}

/** Everything stored for one person, which is all [LedgerCalculator] needs. */
data class StoredLedger(
    val flows: List<StoredFlow> = emptyList(),
    val entries: List<StoredEntry> = emptyList(),
    val funds: List<StoredFund> = emptyList(),
    val movements: List<StoredMovement> = emptyList(),
    val currency: String = "MYR",
)
