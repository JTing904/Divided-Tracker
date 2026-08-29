package com.dividendstream.api.ledger

/** Which way the money moves. Amounts are always stored positive; this carries the sign. */
enum class FlowDirection { INCOME, EXPENSE }

/**
 * How often a recurring figure repeats.
 *
 * All four are first-class. A salary is monthly, an allowance is often daily or weekly, an
 * insurance premium is yearly -- and asking someone to convert their figure into a single
 * supported period would be the application doing arithmetic *at* the user rather than for
 * them. Nothing downstream cares which one is used: each flow's rate is derived from its own
 * period, and the rates simply add up.
 */
enum class CashFlowPeriod { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * The stretch of time the ledger screen is showing.
 *
 * A view, not a separate set of books. The month is where the running figures live, because
 * that is the period a salary and a fund are denominated in; [DAY] narrows the same data down
 * to today. Overspending today therefore shows as a negative day *and* stays in the month --
 * midnight is not a reason for money that was spent to stop having been spent.
 */
enum class LedgerPeriod { DAY, MONTH }
