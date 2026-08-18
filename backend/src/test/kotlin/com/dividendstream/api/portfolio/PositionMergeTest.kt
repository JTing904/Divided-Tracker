package com.dividendstream.api.portfolio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PositionMergeTest {

    private fun merge(q1: String, p1: String, q2: String, p2: String) =
        PositionMerge.merge(BigDecimal(q1), BigDecimal(p1), BigDecimal(q2), BigDecimal(p2))

    @Test
    @DisplayName("buying more at a different price gives the weighted average")
    fun `weighted average of two purchases`() {
        // 100 at 10.00 is 1000, 100 at 11.00 is 1100; 2100 over 200 shares is 10.50.
        val merged = merge("100", "10.0000", "100", "11.0000")

        assertThat(merged.quantity).isEqualByComparingTo("200")
        assertThat(merged.averagePrice).isEqualByComparingTo("10.5000")
    }

    @Test
    @DisplayName("the average is weighted by size, not a midpoint of the two prices")
    fun `unequal purchases are weighted`() {
        // The naive midpoint would be 10.50. Weighted: (900*10 + 100*11) / 1000 = 10.10.
        val merged = merge("900", "10.0000", "100", "11.0000")

        assertThat(merged.averagePrice).isEqualByComparingTo("10.1000")
    }

    @Test
    fun `buying at the same price leaves the average alone`() {
        val merged = merge("100", "10.5600", "250", "10.5600")

        assertThat(merged.quantity).isEqualByComparingTo("350")
        assertThat(merged.averagePrice).isEqualByComparingTo("10.5600")
    }

    @Test
    @DisplayName("the running cost is not rounded before dividing")
    fun `no intermediate rounding`() {
        // 3 at 10.005 is 30.015. Rounding that to currency precision first would give 30.02 and
        // an average of 10.0067; the exact figure is 10.0050.
        val merged = merge("1", "10.0000", "2", "10.0075")

        assertThat(merged.averagePrice).isEqualByComparingTo("10.0050")
    }

    @Test
    @DisplayName("a repeating average is rounded once, at the stored scale")
    fun `repeating decimal is rounded to four places`() {
        // (100*10 + 200*11) / 300 = 10.6666...
        val merged = merge("100", "10.0000", "200", "11.0000")

        assertThat(merged.averagePrice).isEqualByComparingTo("10.6667")
        assertThat(merged.averagePrice.scale()).isEqualTo(4)
    }

    @Test
    @DisplayName("adding to nothing is just the purchase, and never divides by zero")
    fun `empty existing position`() {
        val merged = merge("0", "0.0000", "100", "10.5600")

        assertThat(merged.quantity).isEqualByComparingTo("100")
        assertThat(merged.averagePrice).isEqualByComparingTo("10.5600")
    }

    @Test
    fun `fractional quantities are supported`() {
        // (10.5 * 20 + 4.5 * 24) / 15 = (210 + 108) / 15 = 21.20
        val merged = merge("10.5", "20.0000", "4.5", "24.0000")

        assertThat(merged.quantity).isEqualByComparingTo("15")
        assertThat(merged.averagePrice).isEqualByComparingTo("21.2000")
    }

    @Test
    @DisplayName("repeated purchases drift by the last stored digit, and no further")
    fun `repeated merges stay within the stored precision`() {
        // A holding records an average price, not a total cost. Every merge therefore rounds to
        // the stored scale and the next merge builds on the rounded figure, so a long run of
        // purchases cannot land exactly on the true basis. This test pins how far that goes.
        //
        // Storing the total cost instead would make it exact; that is a schema change, and this
        // asserts the real behaviour meanwhile rather than a comfortable approximation of it.
        var quantity = BigDecimal("100")
        var average = BigDecimal("10.0000")
        repeat(50) {
            val merged = PositionMerge.merge(quantity, average, BigDecimal("1"), BigDecimal("10.3333"))
            quantity = merged.quantity
            average = merged.averagePrice
        }

        // True basis: (100*10 + 50*10.3333) / 150 = 10.1111. Fifty roundings put it one unit of
        // the fourth decimal above that -- about one cent on a fifteen-hundred ringgit position.
        assertThat(quantity).isEqualByComparingTo("150")
        assertThat(average).isEqualByComparingTo("10.1112")
        assertThat(average.subtract(BigDecimal("10.1111")).abs())
            .describedAs("drift after 50 purchases")
            .isLessThanOrEqualTo(BigDecimal("0.0001"))
    }
}
