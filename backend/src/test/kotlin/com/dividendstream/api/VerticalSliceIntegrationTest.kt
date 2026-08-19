package com.dividendstream.api

import com.dividendstream.api.support.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * Walks the MVP end to end: register, sign in, add a holding, and watch a live dividend
 * appear with a correct expected amount, rate and accrued value.
 */
@IntegrationTest
@Transactional
class VerticalSliceIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("register -> add holding -> live dividend accumulates")
    fun `completes the core journey`() {
        val token = register("investor@example.com")

        // The stock is discovered through the provider, not hardcoded in the client.
        val results = getJson("/api/stocks/search?query=maybank", token)
        assertThat(results).isNotEmpty
        assertThat(results[0]["symbol"].asText()).isEqualTo("1155")
        assertThat(results[0]["companyName"].asText()).contains("Malayan Banking")

        val holding = addHolding(token, symbol = "1155", quantity = "1000", averagePrice = "9.50")

        // 1,000 shares x RM0.32 = RM320.00 exactly.
        assertThat(holding["expectedDividend"].asText()).isEqualTo("320.00")
        assertThat(holding["quantity"].asText()).isEqualTo("1000.0000")

        val live = getJson("/api/dividends/live", token)
        assertThat(live["totalExpected"].asText()).isEqualTo("320.00")
        assertThat(live["activeStockCount"].asInt()).isEqualTo(1)
        assertThat(live["streams"]).hasSize(1)

        val stream = live["streams"][0]
        assertThat(stream["symbol"].asText()).isEqualTo("1155")
        assertThat(stream["status"].asText()).isEqualTo("ACCUMULATING")

        // Money crosses the wire as a string so no parser can round it to a double.
        assertThat(stream["ratePerSecond"].asText()).doesNotContain("E")
        val rate = BigDecimal(stream["ratePerSecond"].asText())
        assertThat(rate).isGreaterThan(BigDecimal.ZERO)

        // Part way through the cycle: something has accrued, but not the whole dividend.
        val accrued = BigDecimal(live["totalAccrued"].asText())
        assertThat(accrued).isGreaterThan(BigDecimal.ZERO)
        assertThat(accrued).isLessThan(BigDecimal("320.00"))

        // The dashboard's per-day figure is the per-second rate scaled up, not a guess.
        val perDay = BigDecimal(live["rate"]["perDay"].asText())
        assertThat(perDay).isEqualByComparingTo(
            rate.multiply(BigDecimal(86_400)).setScale(2, java.math.RoundingMode.HALF_UP),
        )
    }

    @Test
    @DisplayName("the live value is derived from timestamps, so it survives a restart")
    fun `reports a consistent value across repeated reads`() {
        val token = register("resumer@example.com")
        addHolding(token, "1155", "1000", "9.50")

        val first = getJson("/api/dividends/live", token)
        val second = getJson("/api/dividends/live", token)

        val firstAccrued = BigDecimal(first["totalAccrued"].asText())
        val secondAccrued = BigDecimal(second["totalAccrued"].asText())

        // A second read -- which is what reopening the app does -- resumes from where the
        // clock is, never from zero.
        assertThat(secondAccrued).isGreaterThanOrEqualTo(firstAccrued)
        assertThat(secondAccrued).isGreaterThan(BigDecimal("1.00"))
    }

    @Test
    @DisplayName("multiple holdings combine into one portfolio rate")
    fun `combines several stocks`() {
        val token = register("multi@example.com")
        addHolding(token, "1155", "1000", "9.50") // RM320.00
        addHolding(token, "1023", "500", "6.40") // RM180.00
        addHolding(token, "5347", "300", "13.20") // RM 75.00

        val live = getJson("/api/dividends/live", token)

        assertThat(live["totalExpected"].asText()).isEqualTo("575.00")
        assertThat(live["activeStockCount"].asInt()).isEqualTo(3)

        // The portfolio rate is the sum of the individual stream rates.
        val streamRateSum = live["streams"]
            .filter { it["status"].asText() == "ACCUMULATING" }
            .map { BigDecimal(it["ratePerSecond"].asText()) }
            .reduce(BigDecimal::add)
        assertThat(BigDecimal(live["rate"]["perSecond"].asText())).isEqualByComparingTo(streamRateSum)

        val portfolio = getJson("/api/portfolio", token)
        assertThat(portfolio["holdings"]).hasSize(3)
        assertThat(portfolio["totalExpectedDividend"].asText()).isEqualTo("575.00")
    }

    @Test
    @DisplayName("settled cycles appear as received income, kept separate from estimates")
    fun `separates received income from estimated accumulation`() {
        val token = register("history@example.com")
        addHolding(token, "1155", "1000", "9.50")

        val history = getJson("/api/dividends/history", token)

        // The mock provider's previous cycle has already paid, so it settles on import.
        assertThat(history["totalReceived"].asText()).isEqualTo("320.00")
        assertThat(history["months"]).isNotEmpty
        assertThat(history["months"][0]["items"][0]["status"].asText()).isEqualTo("PAID")
        assertThat(history["months"][0]["items"][0]["paidAmount"].asText()).isEqualTo("320.00")

        // Received money is never mixed into the accumulating estimate.
        val live = getJson("/api/dividends/live", token)
        assertThat(live["totalReceived"].asText()).isEqualTo("320.00")
        assertThat(live["totalExpected"].asText()).isEqualTo("320.00")
        assertThat(BigDecimal(live["totalAccrued"].asText())).isLessThan(BigDecimal("320.00"))
    }

    @Test
    @DisplayName("upcoming dividends are listed for the calendar")
    fun `lists upcoming dividends`() {
        val token = register("calendar@example.com")
        addHolding(token, "1155", "1000", "9.50")
        addHolding(token, "5347", "300", "13.20")

        val upcoming = getJson("/api/dividends/upcoming", token)

        assertThat(upcoming["items"]).hasSize(2)
        assertThat(upcoming["totalExpected"].asText()).isEqualTo("395.00")

        val paymentDates = upcoming["items"].map { it["paymentDate"].asText() }
        assertThat(paymentDates).isSorted
        upcoming["items"].forEach {
            assertThat(it["exDate"].asText()).isNotBlank
            assertThat(it["paymentDate"].asText()).isNotBlank
        }
    }

    @Test
    @DisplayName("resizing a position updates the expected amount and the rate")
    fun `recalculates when a holding changes`() {
        val token = register("resize@example.com")
        val holding = addHolding(token, "1155", "1000", "9.50")

        val before = BigDecimal(getJson("/api/dividends/live", token)["rate"]["perSecond"].asText())

        val updated = mockMvc.perform(
            put("/api/portfolio/${holding["id"].asText()}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":"2000","averagePrice":"9.50"}"""),
        ).andExpect(status().isOk).andReturn()

        assertThat(objectMapper.readTree(updated.response.contentAsString)["expectedDividend"].asText())
            .isEqualTo("640.00")

        val after = BigDecimal(getJson("/api/dividends/live", token)["rate"]["perSecond"].asText())
        assertThat(after).isGreaterThan(before)
    }

    @Test
    @DisplayName("closing a position drops its unsettled dividends but keeps its history")
    fun `removes unsettled entitlements on delete`() {
        val token = register("closer@example.com")
        val holding = addHolding(token, "1155", "1000", "9.50")

        mockMvc.perform(
            delete("/api/portfolio/${holding["id"].asText()}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNoContent)

        val live = getJson("/api/dividends/live", token)
        assertThat(live["streams"]).isEmpty()
        assertThat(live["totalExpected"].asText()).isEqualTo("0.00")

        // Income that was genuinely received is not erased by selling the position.
        assertThat(getJson("/api/dividends/history", token)["totalReceived"].asText()).isEqualTo("320.00")
    }

    @Test
    @DisplayName("buying more of a held stock enlarges the position at a weighted average")
    fun `adding the same stock again merges the position`() {
        val token = register("topup@example.com")
        addHolding(token, "1155", "1000", "9.50")

        // (1000 * 9.50 + 500 * 11.00) / 1500 = 15000 / 1500 = 10.00
        val merged = addHolding(token, "1155", "500", "11.00")

        assertThat(merged["quantity"].asText()).isEqualTo("1500.0000")
        assertThat(merged["averagePrice"].asText()).isEqualTo("10.0000")

        // One position, not two: the portfolio is a set of holdings, one per stock.
        val portfolio = getJson("/api/portfolio", token)
        assertThat(portfolio["holdings"]).hasSize(1)
        assertThat(portfolio["totalCostBasis"].asText()).isEqualTo("15000.00")
    }

    @Test
    @DisplayName("the same purchase sent twice is applied once")
    fun `idempotency key makes a repeat harmless`() {
        val token = register("queued@example.com")
        val key = java.util.UUID.randomUUID().toString()
        val body = """{"idempotencyKey":"$key","symbol":"1155","quantity":"1000","averagePrice":"9.50"}"""

        // What a queued purchase does when the reply is lost: send it again, unchanged.
        repeat(2) {
            mockMvc.perform(
                post("/api/portfolio")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().is2xxSuccessful)
        }

        // Not two thousand shares, and not two holdings.
        val portfolio = getJson("/api/portfolio", token)
        assertThat(portfolio["holdings"]).hasSize(1)
        assertThat(portfolio["holdings"][0]["quantity"].asText()).isEqualTo("1000.0000")
        assertThat(portfolio["totalCostBasis"].asText()).isEqualTo("9500.00")
    }

    @Test
    @DisplayName("a different key is a different purchase, and does add again")
    fun `distinct keys both apply`() {
        val token = register("twobuys@example.com")

        listOf(java.util.UUID.randomUUID(), java.util.UUID.randomUUID()).forEach { key ->
            mockMvc.perform(
                post("/api/portfolio")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"idempotencyKey":"$key","symbol":"1155","quantity":"500","averagePrice":"10.00"}"""),
            ).andExpect(status().is2xxSuccessful)
        }

        // The guard must not swallow a genuine second purchase of the same stock.
        val portfolio = getJson("/api/portfolio", token)
        assertThat(portfolio["holdings"]).hasSize(1)
        assertThat(portfolio["holdings"][0]["quantity"].asText()).isEqualTo("1000.0000")
    }

    @Test
    @DisplayName("one user's key cannot resolve to another user's holding")
    fun `keys are scoped to the user`() {
        val mine = register("mine@example.com")
        val theirs = register("theirs@example.com")
        val key = java.util.UUID.randomUUID().toString()

        mockMvc.perform(
            post("/api/portfolio")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $mine")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idempotencyKey":"$key","symbol":"1155","quantity":"1000","averagePrice":"9.50"}"""),
        ).andExpect(status().is2xxSuccessful)

        // The same key from somebody else is a new purchase in their own portfolio, never a
        // window onto the first one's.
        mockMvc.perform(
            post("/api/portfolio")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $theirs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idempotencyKey":"$key","symbol":"1295","quantity":"200","averagePrice":"4.00"}"""),
        ).andExpect(status().is2xxSuccessful)

        val theirPortfolio = getJson("/api/portfolio", theirs)
        assertThat(theirPortfolio["holdings"]).hasSize(1)
        assertThat(theirPortfolio["holdings"][0]["symbol"].asText()).isEqualTo("1295")
    }

    // --- helpers -----------------------------------------------------------------

    private fun register(email: String): String {
        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Test Investor","email":"$email","password":"correct-horse-battery"}"""),
        ).andExpect(status().isCreated).andReturn()

        return objectMapper.readTree(result.response.contentAsString)["accessToken"].asText()
    }

    private fun addHolding(token: String, symbol: String, quantity: String, averagePrice: String): JsonNode {
        val result = mockMvc.perform(
            post("/api/portfolio")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"symbol":"$symbol","quantity":"$quantity","averagePrice":"$averagePrice"}"""),
        ).andExpect(status().isCreated).andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun getJson(path: String, token: String): JsonNode {
        val result = mockMvc.perform(
            get(path).header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isOk).andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }
}
