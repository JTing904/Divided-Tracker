package com.dividendstream.app.domain

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
