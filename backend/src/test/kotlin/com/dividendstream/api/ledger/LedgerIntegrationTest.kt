package com.dividendstream.api.ledger

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@IntegrationTest
@Transactional
class LedgerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("a salary starts accruing per second without anything being written per second")
    fun `declared income produces a live rate`() {
        val token = register("earner@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")

        val ledger = ledger(token)

        assertThat(ledger["incomeRatePerSecond"].money()).isGreaterThan(BigDecimal.ZERO)
        assertThat(ledger["netRatePerSecond"].money()).isGreaterThan(BigDecimal.ZERO)
        assertThat(ledger["plannedIncome"].money()).isEqualByComparingTo(BigDecimal("3000.00"))
        assertThat(ledger["flows"]).hasSize(1)
    }

    @Test
    @DisplayName("a daily allowance is a first-class option, not a monthly figure in disguise")
    fun `daily income is supported`() {
        val token = register("student@example.com")

        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"20.00","period":"DAILY"}""")

        val ledger = ledger(token)
        val planned = ledger["plannedIncome"].money()

        // RM20 a day across a whole month: somewhere between RM560 (February) and RM620.
        assertThat(planned).isBetween(BigDecimal("560.00"), BigDecimal("620.00"))
        assertThat(ledger["flows"][0]["period"].asText()).isEqualTo("DAILY")
    }

    @Test
    @DisplayName("spending more than you earn shows a negative rate rather than a floor of zero")
    fun `expenses can outweigh income`() {
        val token = register("overspender@example.com")

        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"300.00","period":"MONTHLY"}""")
        saveFlow(token, """{"name":"Rent","direction":"EXPENSE","amount":"800.00","period":"MONTHLY"}""")

        val ledger = ledger(token)

        assertThat(ledger["netRatePerSecond"].money()).isLessThan(BigDecimal.ZERO)
        assertThat(ledger["rate"]["perMonth"].money()).isEqualByComparingTo(BigDecimal("-500.00"))
        assertThat(ledger["netAccrued"].money()).isLessThan(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("projections and records are reported apart, never added together")
    fun `recorded entries stay separate from projections`() {
        val token = register("careful@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        saveEntry(token, """{"direction":"EXPENSE","amount":"45.50","category":"Food","note":"Lunch"}""")

        val ledger = ledger(token)

        // The projection knows nothing of the lunch, and the record knows nothing of the salary.
        assertThat(ledger["plannedIncome"].money()).isEqualByComparingTo(BigDecimal("3000.00"))
        assertThat(ledger["actualExpense"].money()).isEqualByComparingTo(BigDecimal("45.50"))
        assertThat(ledger["actualIncome"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(ledger["actualNet"].money()).isEqualByComparingTo(BigDecimal("-45.50"))
        assertThat(ledger["entries"]).hasSize(1)
    }

    @Test
    @DisplayName("sending the same save twice records it once")
    fun `saves are idempotent when an id is supplied`() {
        val token = register("doubletap@example.com")
        val id = UUID.randomUUID().toString()
        val body = """{"id":"$id","direction":"EXPENSE","amount":"12.00","category":"Coffee"}"""

        saveEntry(token, body)
        saveEntry(token, body)

        assertThat(ledger(token)["entries"]).hasSize(1)
    }

    @Test
    @DisplayName("resending a flow with the same id updates it rather than duplicating it")
    fun `flow saves are idempotent`() {
        val token = register("raise@example.com")
        val id = UUID.randomUUID().toString()

        saveFlow(token, """{"id":"$id","name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        saveFlow(token, """{"id":"$id","name":"Salary","direction":"INCOME","amount":"3500.00","period":"MONTHLY"}""")

        val ledger = ledger(token)

        assertThat(ledger["flows"]).hasSize(1)
        assertThat(ledger["flows"][0]["amount"].money()).isEqualByComparingTo(BigDecimal("3500.00"))
    }

    @Test
    @DisplayName("one person cannot see, change or delete another person's ledger")
    fun `isolates users from each other`() {
        val alice = register("ledger-alice@example.com")
        val bob = register("ledger-bob@example.com")

        val flowId = objectMapper.readTree(
            saveFlow(alice, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}"""),
        )["id"].asText()
        val entryId = objectMapper.readTree(
            saveEntry(alice, """{"direction":"EXPENSE","amount":"9.90"}"""),
        )["id"].asText()

        // Bob's ledger is empty, whatever Alice has in hers.
        assertThat(ledger(bob)["flows"]).isEmpty()
        assertThat(ledger(bob)["entries"]).isEmpty()

        mockMvc.perform(delete("/api/ledger/flows/$flowId").header(HttpHeaders.AUTHORIZATION, "Bearer $bob"))
            .andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/ledger/entries/$entryId").header(HttpHeaders.AUTHORIZATION, "Bearer $bob"))
            .andExpect(status().isNotFound)

        // A save aimed at an id Bob does not own is a 404, not a takeover.
        mockMvc.perform(
            post("/api/ledger/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$flowId","name":"Mine now","direction":"INCOME","amount":"1.00","period":"MONTHLY"}"""),
        ).andExpect(status().isNotFound)

        // And Alice's is untouched by any of it.
        assertThat(ledger(alice)["flows"]).hasSize(1)
        assertThat(ledger(alice)["flows"][0]["name"].asText()).isEqualTo("Salary")
    }

    @Test
    fun `refuses a negative amount`() {
        val token = register("negative@example.com")

        mockMvc.perform(
            post("/api/ledger/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Odd","direction":"INCOME","amount":"-50.00","period":"MONTHLY"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `refuses an end date before the start date`() {
        val token = register("backwards@example.com")

        mockMvc.perform(
            post("/api/ledger/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"Odd","direction":"INCOME","amount":"50.00","period":"MONTHLY",""" +
                        """"startsOn":"2026-08-01","endsOn":"2026-07-01"}""",
                ),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `requires a session`() {
        mockMvc.perform(get("/api/ledger")).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("what is left over is split between the funds by share")
    fun `funds take a percentage of the surplus`() {
        val token = register("saver@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        saveFlow(token, """{"name":"Rent","direction":"EXPENSE","amount":"1000.00","period":"MONTHLY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        saveFund(token, """{"name":"Travel","percent":"25.00"}""")

        val ledger = ledger(token)

        // RM2,000 left over: half to one, a quarter to the other, a quarter unclaimed.
        assertThat(ledger["funds"]).hasSize(2)
        assertThat(ledger["funds"][0]["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(ledger["funds"][1]["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(ledger["allocatedPercent"].money()).isEqualByComparingTo(BigDecimal("75.00"))
        assertThat(ledger["unallocatedPercent"].money()).isEqualByComparingTo(BigDecimal("25.00"))
        // Each fund fills per second, like everything else on this screen.
        assertThat(ledger["funds"][0]["ratePerSecond"].money()).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("a deficit is not divided up -- funds receive nothing rather than a negative")
    fun `funds receive nothing when there is no surplus`() {
        val token = register("broke@example.com")

        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"300.00","period":"MONTHLY"}""")
        saveFlow(token, """{"name":"Rent","direction":"EXPENSE","amount":"800.00","period":"MONTHLY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fund = ledger(token)["funds"][0]

        assertThat(fund["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["accruedThisMonth"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["ratePerSecond"].money()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("the shares cannot add up past the whole, and the refusal says how much is free")
    fun `funds cannot exceed one hundred percent`() {
        val token = register("greedy@example.com")

        saveFund(token, """{"name":"Emergency","percent":"70.00"}""")

        mockMvc.perform(
            post("/api/ledger/funds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Travel","percent":"40.00"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("30.00%")))

        assertThat(ledger(token)["funds"]).hasSize(1)
    }

    @Test
    @DisplayName("raising an existing fund is measured against the others, not against itself")
    fun `updating a fund does not count itself twice`() {
        val token = register("adjuster@example.com")
        val id = UUID.randomUUID().toString()

        saveFund(token, """{"id":"$id","name":"Emergency","percent":"60.00"}""")
        // 70 would break the limit if the old 60 were still counted alongside it.
        saveFund(token, """{"id":"$id","name":"Emergency","percent":"70.00"}""")

        val ledger = ledger(token)

        assertThat(ledger["funds"]).hasSize(1)
        assertThat(ledger["allocatedPercent"].money()).isEqualByComparingTo(BigDecimal("70.00"))
    }

    @Test
    @DisplayName("the month list has no gaps, so a quiet month reads as quiet rather than missing")
    fun `history covers every month`() {
        val token = register("history@example.com")

        val months = ledger(token)["months"]

        assertThat(months).hasSize(12)
        assertThat(months[0]["entryCount"].asInt()).isZero()
    }

    // --- helpers -------------------------------------------------------------

    private fun register(email: String): String {
        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Test Person","email":"$email","password":"correct-horse-battery"}"""),
        ).andExpect(status().isCreated).andReturn()

        return objectMapper.readTree(result.response.contentAsString)["accessToken"].asText()
    }

    private fun saveFlow(token: String, body: String): String =
        mockMvc.perform(
            post("/api/ledger/flows")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun saveEntry(token: String, body: String): String =
        mockMvc.perform(
            post("/api/ledger/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun saveFund(token: String, body: String): String =
        mockMvc.perform(
            post("/api/ledger/funds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun ledger(token: String): JsonNode {
        val result = mockMvc.perform(
            get("/api/ledger").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isOk).andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }

    /**
     * Money crosses the wire as a JSON *string*, not a number -- see `JacksonConfig`, which
     * does that deliberately so a 12dp rate is not flattened into a double by the client's
     * parser. `JsonNode.decimalValue()` on a text node quietly answers zero, so reading these
     * fields as numbers would make every assertion below pass against nothing.
     */
    private fun JsonNode.money(): BigDecimal = BigDecimal(asText())
}
