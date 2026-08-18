package com.dividendstream.api.dividend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PaymentLagTest {

    private fun observed(vararg pairs: Pair<String, String>) = PaymentLag.infer(
        pairs.map { (ex, paid) ->
            PaymentLag.Observation(LocalDate.parse(ex), LocalDate.parse(paid))
        },
    )

    @Test
    @DisplayName("nothing observed means nothing inferred, so the caller keeps its default")
    fun `no observations`() {
        assertThat(PaymentLag.infer(emptyList())).isNull()
    }

    @Test
    fun `a single observation is enough to beat a guess`() {
        assertThat(observed("2025-12-12" to "2026-01-08")).isEqualTo(27)
    }

    @Test
    @DisplayName("the median is used, so one delayed cycle does not move every estimate")
    fun `median ignores an outlier`() {
        // Four ordinary cycles and one that landed over a long holiday.
        val lag = observed(
            "2024-03-15" to "2024-04-11", // 27
            "2024-12-17" to "2025-01-13", // 27
            "2025-03-13" to "2025-04-09", // 27
            "2025-12-12" to "2026-02-04", // 54, the outlier
        )

        assertThat(lag).isEqualTo(27)
    }

    @Test
    @DisplayName("an impossible observation is dropped rather than averaged in")
    fun `rejects nonsense`() {
        // Paid before it went ex: a typo, and one that would drag the median down if trusted.
        assertThat(observed("2026-01-11" to "2025-12-12")).isNull()

        // The same day is not a lag either.
        assertThat(observed("2026-01-11" to "2026-01-11")).isNull()

        // A mistyped year reads as a lag of a year, which is not a payment lag.
        assertThat(observed("2025-12-12" to "2027-01-08")).isNull()
    }

    @Test
    @DisplayName("good observations survive alongside a bad one")
    fun `keeps the plausible ones`() {
        val lag = observed(
            "2025-03-13" to "2025-04-09", // 27
            "2025-12-12" to "2024-01-08", // mistyped year, negative
            "2024-03-15" to "2024-04-13", // 29
        )

        // Median of the two that made sense.
        assertThat(lag).isEqualTo(29)
    }

    @Test
    fun `two observations take the upper of the pair`() {
        // No principled midpoint for an even count; the later is the safer of the two, since
        // an estimate that pays late is a smaller lie than one that says the money is due.
        assertThat(observed("2025-03-13" to "2025-04-09", "2024-03-15" to "2024-04-13"))
            .isEqualTo(29)
    }
}
