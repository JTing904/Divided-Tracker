package com.dividendstream.api.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Central definition of monetary precision.
 *
 * Money is never represented as [Double] or [Float] anywhere in this codebase. The scales
 * differ by role: a settled amount needs cents, a per-second rate needs far more digits
 * because it is multiplied by a large elapsed-second count before anyone reads it.
 */
object Money {

    /** Settled/expected amounts, e.g. RM320.00. */
    const val AMOUNT_SCALE = 2

    /** Dividend declared per share, e.g. RM0.32000000. */
    const val PER_SHARE_SCALE = 8

    /** Accumulation rate, e.g. RM0.000020576132 per second. */
    const val RATE_SCALE = 12

    /** Precision of an in-flight estimate shown to the user, e.g. RM128.47382900. */
    const val ACCRUAL_SCALE = 8

    /** Share quantities; scaled to allow fractional shares later without a migration. */
    const val QUANTITY_SCALE = 4

    val ZERO_AMOUNT: BigDecimal = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
    val ZERO_RATE: BigDecimal = BigDecimal.ZERO.setScale(RATE_SCALE)
    val ZERO_ACCRUAL: BigDecimal = BigDecimal.ZERO.setScale(ACCRUAL_SCALE)

    /** Rounds a settled amount to currency precision. */
    fun amount(value: BigDecimal): BigDecimal = value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)

    fun perShare(value: BigDecimal): BigDecimal = value.setScale(PER_SHARE_SCALE, RoundingMode.HALF_UP)

    fun rate(value: BigDecimal): BigDecimal = value.setScale(RATE_SCALE, RoundingMode.HALF_UP)

    /**
     * Rounds an *estimate*. Deliberately rounds DOWN: an unpaid, still-accumulating figure
     * should never be shown larger than it actually is.
     */
    fun accrual(value: BigDecimal): BigDecimal = value.setScale(ACCRUAL_SCALE, RoundingMode.DOWN)

    fun quantity(value: BigDecimal): BigDecimal = value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)
}
