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
    @DisplayName("writing down what you spent takes it off what is left")
    fun `records come off the surplus`() {
        val token = register("careful@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        val before = ledger(token)["netAccrued"].money()

        saveEntry(token, """{"direction":"EXPENSE","amount":"45.50","category":"Food","note":"Lunch"}""")
        val after = ledger(token)

        // The lunch is gone from what is left. It is still reported on its own as well, so the
        // screen can say how much of the total came from records rather than from the plan.
        assertThat(after["netAccrued"].money()).isLessThan(before)
        assertThat(after["recordedNet"].money()).isEqualByComparingTo(BigDecimal("-45.50"))
        assertThat(after["actualExpense"].money()).isEqualByComparingTo(BigDecimal("45.50"))
        assertThat(after["actualIncome"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(after["entries"]).hasSize(1)
    }

    @Test
    @DisplayName("the day view measures the same data over today alone")
    fun `a day is a narrower window on the same ledger`() {
        val token = register("daily@example.com")

        saveFlow(
            token,
            """{"name":"Salary","direction":"INCOME","amount":"3100.00","period":"MONTHLY",""" +
                """"startsOn":"2020-01-01"}""",
        )

        val month = ledger(token)
        val day = ledger(token, "DAY")

        assertThat(month["period"].asText()).isEqualTo("MONTH")
        assertThat(day["period"].asText()).isEqualTo("DAY")
        // One day of a month's salary is a fraction of it, never the whole.
        assertThat(day["plannedIncome"].money()).isLessThan(month["plannedIncome"].money())
        assertThat(day["plannedIncome"].money()).isGreaterThan(BigDecimal.ZERO)
        assertThat(day["periodLabel"].asText()).hasSize(10)
        assertThat(month["periodLabel"].asText()).hasSize(7)
    }

    @Test
    @DisplayName("overspending today shows as a negative day and stays in the month")
    fun `a day can go negative without the month forgetting`() {
        val token = register("overspent@example.com")

        // RM31 a day of income, against RM500 spent today.
        saveFlow(
            token,
            """{"name":"Allowance","direction":"INCOME","amount":"31.00","period":"DAILY",""" +
                """"startsOn":"2020-01-01"}""",
        )
        saveEntry(token, """{"direction":"EXPENSE","amount":"500.00","category":"Fun"}""")

        val day = ledger(token, "DAY")
        val month = ledger(token)

        assertThat(day["netAccrued"].money()).isLessThan(BigDecimal.ZERO)
        // Midnight is not a reason for money that was spent to stop having been spent: the
        // month carries the same RM500.
        assertThat(month["actualExpense"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(month["recordedNet"].money()).isEqualByComparingTo(BigDecimal("-500.00"))
    }

    @Test
    @DisplayName("switching to the day view does not make a fund look smaller")
    fun `funds are answered from the month whichever view is on`() {
        val token = register("steadyfund@example.com")

        saveFlow(
            token,
            """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY",""" +
                """"startsOn":"2020-01-01"}""",
        )
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fromMonth = ledger(token)["funds"][0]["plannedThisMonth"].money()
        val fromDay = ledger(token, "DAY")["funds"][0]["plannedThisMonth"].money()

        assertThat(fromDay).isEqualByComparingTo(fromMonth)
        assertThat(fromDay).isEqualByComparingTo(BigDecimal("1500.00"))
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

    @Test
    @DisplayName("the share set on a fund fills it by itself, without anything being pressed")
    fun `a fund fills from its percentage`() {
        val token = register("autosaver@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        saveFlow(token, """{"name":"Rent","direction":"EXPENSE","amount":"1000.00","period":"MONTHLY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fund = ledger(token)["funds"][0]

        // RM2,000 left over this month; half of it is earmarked, and the fund already holds
        // whatever share of that has accrued so far. Nobody deposited anything.
        assertThat(fund["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal("1000.00"))
        assertThat(fund["paidIn"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["balance"].money()).isGreaterThan(BigDecimal.ZERO)
        assertThat(fund["balance"].money()).isLessThanOrEqualTo(BigDecimal("1000.00"))
    }

    @Test
    @DisplayName("a fund with nothing left over holds nothing, rather than a negative")
    fun `a deficit leaves a fund empty`() {
        val token = register("emptyfund@example.com")

        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"300.00","period":"MONTHLY"}""")
        saveFlow(token, """{"name":"Rent","direction":"EXPENSE","amount":"800.00","period":"MONTHLY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fund = ledger(token)["funds"][0]

        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["accruedThisMonth"].money()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("spending out of a fund takes it off the balance and stays off")
    fun `a withdrawal reduces the balance`() {
        val token = register("spender@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()

        val before = ledger(token)["funds"][0]["balance"].money()
        move(token, fundId, """{"direction":"WITHDRAWAL","amount":"10.00","note":"Flights"}""")
        val after = ledger(token)["funds"][0]

        assertThat(after["takenOut"].money()).isEqualByComparingTo(BigDecimal("10.00"))
        // The balance keeps growing per second, so it is compared against what it would have
        // been rather than to an exact figure: the withdrawal is what moved it down.
        assertThat(after["balance"].money()).isLessThan(before)
        assertThat(after["movements"]).hasSize(1)
    }

    @Test
    @DisplayName("money can also be put in by hand, on top of the share")
    fun `a deposit adds to the balance`() {
        val token = register("topup@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Emergency","percent":"50.00"}"""),
        )["id"].asText()

        move(token, fundId, """{"direction":"DEPOSIT","amount":"500.00","note":"Angpow"}""")

        val fund = ledger(token)["funds"][0]

        assertThat(fund["paidIn"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(ledger(token)["totalFundBalance"].money()).isEqualByComparingTo(BigDecimal("500.00"))
    }

    @Test
    @DisplayName("a fund may be borrowed from, and goes below zero when it is")
    fun `a fund can go negative`() {
        val token = register("borrower@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()

        move(token, fundId, """{"direction":"DEPOSIT","amount":"100.00"}""")
        move(token, fundId, """{"direction":"WITHDRAWAL","amount":"250.00","note":"Flights"}""")

        val fund = ledger(token)["funds"][0]

        // Owed back, not refused. Refusing would have forced a deposit that never happened
        // in order to describe a withdrawal that did.
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal("-150.00"))
        assertThat(fund["carriedOver"].money()).isEqualByComparingTo(BigDecimal("-150.00"))
        assertThat(fund["takenOut"].money()).isEqualByComparingTo(BigDecimal("250.00"))
    }

    @Test
    @DisplayName("the share pays a borrowed fund back by itself")
    fun `a negative fund refills from its percentage`() {
        val token = register("repayer@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"50.00"}"""),
        )["id"].asText()

        move(token, fundId, """{"direction":"WITHDRAWAL","amount":"1000.00"}""")

        val fund = ledger(token)["funds"][0]

        // Still in debt for now, but this month's share is already counting against it, and
        // next month's will too -- the balance is the debt plus whatever has accrued since.
        assertThat(fund["carriedOver"].money()).isEqualByComparingTo(BigDecimal("-1000.00"))
        assertThat(fund["accruedThisMonth"].money()).isGreaterThanOrEqualTo(BigDecimal.ZERO)
        assertThat(fund["balance"].money())
            .isEqualByComparingTo(
                fund["carriedOver"].money().add(fund["accruedThisMonth"].money()),
            )
    }

    @Test
    @DisplayName("a borrowed fund drags the total down with it")
    fun `the total across funds can be negative`() {
        val token = register("indebted@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()

        move(token, fundId, """{"direction":"WITHDRAWAL","amount":"400.00"}""")

        assertThat(ledger(token)["totalFundBalance"].money())
            .isEqualByComparingTo(BigDecimal("-400.00"))
    }

    @Test
    @DisplayName("a fund created before this month carries what earlier months put aside")
    fun `earlier months are carried over`() {
        val token = register("carried@example.com")

        // A salary that has been running since the start of last year.
        saveFlow(
            token,
            """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY",""" +
                """"startsOn":"2020-01-01"}""",
        )
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fund = ledger(token)["funds"][0]

        // The fund itself was created a moment ago, so no month has finished under it yet and
        // nothing is carried over. What it holds is this month's share, and only that.
        assertThat(fund["earmarkedEarlier"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["carriedOver"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(fund["balance"].money())
            .isEqualByComparingTo(fund["accruedThisMonth"].money())
    }

    @Test
    @DisplayName("a mistyped movement is corrected in place, not doubled")
    fun `resending a movement with its id corrects it`() {
        val token = register("typo@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()

        val movementId = objectMapper.readTree(
            move(token, fundId, """{"direction":"DEPOSIT","amount":"5000.00"}"""),
        )["id"].asText()
        // Meant RM500, typed RM5000.
        move(token, fundId, """{"id":"$movementId","direction":"DEPOSIT","amount":"500.00"}""")

        val fund = ledger(token)["funds"][0]

        assertThat(fund["movements"]).hasSize(1)
        assertThat(fund["paidIn"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal("500.00"))
    }

    @Test
    @DisplayName("a movement that never happened can be removed")
    fun `a movement can be deleted`() {
        val token = register("undo@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()
        val movementId = objectMapper.readTree(
            move(token, fundId, """{"direction":"DEPOSIT","amount":"250.00"}"""),
        )["id"].asText()

        mockMvc.perform(
            delete("/api/ledger/movements/$movementId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNoContent)

        val fund = ledger(token)["funds"][0]
        assertThat(fund["movements"]).isEmpty()
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("deleting a fund takes its movements with it")
    fun `movements do not outlive their fund`() {
        val token = register("tidy@example.com")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Travel","percent":"30.00"}"""),
        )["id"].asText()
        move(token, fundId, """{"direction":"DEPOSIT","amount":"100.00"}""")

        mockMvc.perform(
            delete("/api/ledger/funds/$fundId").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNoContent)

        val after = ledger(token)
        assertThat(after["funds"]).isEmpty()
        assertThat(after["totalFundBalance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("one person cannot move money in another person's fund")
    fun `movements are scoped to the owner`() {
        val alice = register("move-alice@example.com")
        val bob = register("move-bob@example.com")
        val fundId = objectMapper.readTree(
            saveFund(alice, """{"name":"Emergency","percent":"50.00"}"""),
        )["id"].asText()

        mockMvc.perform(
            post("/api/ledger/funds/$fundId/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"direction":"DEPOSIT","amount":"100.00"}"""),
        ).andExpect(status().isNotFound)
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

    private fun move(token: String, fundId: String, body: String): String =
        mockMvc.perform(
            post("/api/ledger/funds/$fundId/movements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun ledger(token: String, period: String = "MONTH"): JsonNode {
        val result = mockMvc.perform(
            get("/api/ledger").param("period", period)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
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
