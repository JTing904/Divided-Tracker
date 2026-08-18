package com.dividendstream.api.portfolio

import com.dividendstream.api.common.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Combines an existing position with a further purchase of the same stock.
 *
 * Buying more of something already held does not replace the position, it enlarges it, and the
 * average price afterwards is the one figure nobody should be asked to work out by hand: get it
 * wrong once and the cost basis is wrong for as long as the holding exists.
 *
 * Pure and separate from the service so the arithmetic can be checked on its own, which for
 * money is worth the extra file.
 */
object PositionMerge {

    data class Position(val quantity: BigDecimal, val averagePrice: BigDecimal)

    /**
     * Total money in, divided by total shares held.
     *
     * Both products are exact -- BigDecimal multiplication does not round -- and the single
     * rounding happens on the final division. Rounding the running cost to currency precision
     * first would quietly lose fractions of a cent on every purchase, and those accumulate in
     * one direction.
     */
    fun merge(
        existingQuantity: BigDecimal,
        existingAveragePrice: BigDecimal,
        purchasedQuantity: BigDecimal,
        purchasePrice: BigDecimal,
    ): Position {
        val totalQuantity = existingQuantity + purchasedQuantity

        // Nothing sensible to average against; the purchase price is the whole position. Also
        // guards the division below, which would otherwise be by zero.
        if (totalQuantity.signum() <= 0) {
            return Position(
                quantity = Money.quantity(totalQuantity),
                averagePrice = purchasePrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
            )
        }

        val totalCost = existingQuantity.multiply(existingAveragePrice) +
            purchasedQuantity.multiply(purchasePrice)

        return Position(
            quantity = Money.quantity(totalQuantity),
            averagePrice = totalCost.divide(totalQuantity, PRICE_SCALE, RoundingMode.HALF_UP),
        )
    }

    /** Matches the scale the holding stores, so nothing is rounded twice on the way in. */
    const val PRICE_SCALE = 4
}
