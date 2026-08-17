package com.dividendstream.api.dividend

/**
 * Lifecycle of one user's entitlement to one dividend cycle.
 *
 * ```
 *          ex-date              payment date        job runs
 * UPCOMING --------> ACCUMULATING ---------> PAYABLE --------> PAID
 * ```
 * CANCELLED is terminal and reachable from any pre-payment state (the company withdraws
 * the dividend, or the user sells the position before the ex-date).
 */
enum class DividendStatus {
    /** Ex-date has not arrived; nothing has accrued yet. */
    UPCOMING,

    /** Between ex-date and payment date; the live counter is ticking. */
    ACCUMULATING,

    /** Payment date reached, full expected amount accrued, settlement not yet recorded. */
    PAYABLE,

    /** Settled. `paid_amount` is the authoritative figure -- not the estimate. */
    PAID,

    CANCELLED,
    ;

    /** True while the value still moves or awaits settlement. */
    val isActive: Boolean
        get() = this == UPCOMING || this == ACCUMULATING || this == PAYABLE
}
