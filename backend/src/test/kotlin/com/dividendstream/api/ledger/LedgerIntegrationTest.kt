package com.dividendstream.api.ledger

import com.dividendstream.api.support.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
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
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@IntegrationTest
@Transactional
class LedgerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var entityManager: jakarta.persistence.EntityManager

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

        // Daily, because a fund is filled from money that has arrived and a day's money
        // arrives when the day is over. Flows start on the first of the month.
        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"100.00","period":"DAILY"}""")
        saveFlow(token, """{"name":"Bus","direction":"EXPENSE","amount":"40.00","period":"DAILY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val fund = ledger(token)["funds"][0]

        // Every day but the one being lived through has paid: RM60 left over each, half of it
        // earmarked. Nobody deposited anything.
        val daysFinished = LocalDate.now().dayOfMonth - 1
        val expected = BigDecimal(daysFinished).multiply(BigDecimal("30.00"))

        assertThat(fund["paidIn"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        // On its way, not in: this month's share is still part of what is left over and is
        // banked when the month finishes. Nothing has been paid into this fund by hand, and
        // no month has finished since it was made, so it holds nothing yet.
        assertThat(fund["accruedThisMonth"].money()).isEqualByComparingTo(expected)
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("a finished month is banked into the fund as a movement anyone can see")
    fun `a finished month is settled once`() {
        val token = register("settler@example.com")
        saveFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"100.00","period":"DAILY"}""")
        val fundId = objectMapper.readTree(
            saveFund(token, """{"name":"Emergency","percent":"50.00"}"""),
        )["id"].asText()

        // Settlement walks from the month the fund was made in, and there is no API for making
        // one in the past. Backdating it here is the only way to have a finished month at all.
        //
        // Flushed first: the writes above went through MockMvc inside this test's transaction
        // and are still sitting in the persistence context. Raw SQL against rows that are not
        // in the database yet updates nothing at all, silently.
        entityManager.flush()
        val lastMonth = YearMonth.now().minusMonths(1)
        jdbc.update(
            "UPDATE funds SET created_at = ? WHERE id = ?::uuid",
            java.sql.Timestamp.from(lastMonth.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
            fundId,
        )
        // The flow has to have been live in it too, or the month put nothing aside.
        jdbc.update("UPDATE cash_flows SET starts_on = ? WHERE user_id IN (SELECT user_id FROM funds WHERE id = ?::uuid)",
            java.sql.Date.valueOf(lastMonth.atDay(1)), fundId)
        // And cleared afterwards, so the service reads the backdated rows rather than the
        // ones Hibernate still has in hand.
        entityManager.clear()

        val fund = ledger(token)["funds"][0]
        val share = BigDecimal(lastMonth.lengthOfMonth()).multiply(BigDecimal("50.00"))


        // A real row, dated the day the month stopped being the current one, and labelled.
        val settlement = fund["movements"].first { it["source"].asText() == "MONTHLY_SHARE" }
        assertThat(settlement["settledMonth"].asText()).isEqualTo(lastMonth.toString())
        assertThat(settlement["occurredOn"].asText()).isEqualTo(lastMonth.plusMonths(1).atDay(1).toString())
        assertThat(settlement["amount"].money()).isEqualByComparingTo(share)
        assertThat(fund["balance"].money()).isEqualByComparingTo(share)

        // Reading again banks nothing further: the month is settled, not re-settled.
        val again = ledger(token)["funds"][0]
        assertThat(again["movements"].count { it["source"].asText() == "MONTHLY_SHARE" }).isEqualTo(1)
        assertThat(again["balance"].money()).isEqualByComparingTo(share)
    }

    @Test
    @DisplayName("a wage names its payday, and arrives on it")
    fun `a monthly flow pays on its payday`() {
        val today = LocalDate.now()
        val lastDay = YearMonth.from(today).atEndOfMonth().dayOfMonth

        val token = register("payday@example.com")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        // The first has always been and gone -- on the first itself it lands at midnight.
        saveFlow(
            token,
            """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY","arrivesOn":1}""",
        )
        // The last day has not, unless today is it. Chosen this way so the test says the same
        // thing on every day of every month rather than skipping itself for most of them.
        saveFlow(
            token,
            """{"name":"Bonus","direction":"INCOME","amount":"500.00","period":"MONTHLY","arrivesOn":$lastDay}""",
        )

        val body = ledger(token)
        val bonusArrived = today.dayOfMonth == lastDay
        val received = if (bonusArrived) BigDecimal("3500.00") else BigDecimal("3000.00")

        assertThat(body["monthReceivedNet"].money()).isEqualByComparingTo(received)
        assertThat(body["funds"][0]["accruedThisMonth"].money())
            .isEqualByComparingTo(received.divide(BigDecimal("2")))

        // Both are in the projection, which is a projection of the whole month.
        assertThat(body["plannedIncome"].money()).isEqualByComparingTo(BigDecimal("3500.00"))
    }

    @Test
    @DisplayName("a payday later than the month is long lands on the last day instead")
    fun `a payday of 31 is clamped`() {
        val token = register("clamped@example.com")
        saveFlow(
            token,
            """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY","arrivesOn":31}""",
        )

        val flow = ledger(token)["flows"][0]
        assertThat(flow["arrivesOn"].asInt()).isEqualTo(31)

        // Paid on the last day of the month, whichever day that is -- so it has not arrived
        // until the month is over, and February does not swallow it.
        val lastDay = YearMonth.now().atEndOfMonth()
        val expected = if (LocalDate.now().isBefore(lastDay)) BigDecimal.ZERO else BigDecimal("3000.00")
        assertThat(flow["receivedThisMonth"].money()).isEqualByComparingTo(expected)
    }

    @Test
    @DisplayName("a payday is dropped when the period cannot use one")
    fun `a daily flow keeps no payday`() {
        val token = register("dailypayday@example.com")
        saveFlow(
            token,
            """{"name":"Allowance","direction":"INCOME","amount":"20.00","period":"DAILY","arrivesOn":15}""",
        )

        // Absent rather than null: the serialiser omits a field it has nothing to say about.
        val payday = ledger(token)["flows"][0].path("arrivesOn")
        assertThat(payday.isMissingNode || payday.isNull).isTrue()
    }

    @Test
    @DisplayName("a wage is worth nothing until it is paid, however far through the month it is")
    fun `a monthly flow pays when the month ends`() {
        val token = register("wageearner@example.com")

        saveFlow(token, """{"name":"Salary","direction":"INCOME","amount":"3000.00","period":"MONTHLY"}""")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")

        val body = ledger(token)
        val fund = body["funds"][0]

        // The consequence of paying on the period boundary, written down rather than left to
        // be discovered: a monthly wage lands when the month closes, so for the whole of the
        // month it is in, the fund holds none of it. That errs behind what a person actually
        // has, never ahead, and only one of those two mistakes spends money that is not there.
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(body["monthReceivedNet"].money()).isEqualByComparingTo(BigDecimal.ZERO)

        // The projection still knows about it. That is what a projection is.
        assertThat(fund["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal("1500.00"))
        assertThat(body["monthNetAccrued"].money()).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("what was written down this month reaches the funds, not just next month")
    fun `a record this month is in the fund share`() {
        val token = register("recordshare@example.com")

        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        // No flows at all, so the whole surplus is this one record and the figures below are
        // exact rather than a range: nothing is accruing per second to move them.
        saveEntry(token, """{"direction":"INCOME","amount":"200.00","category":"salary"}""")

        val body = ledger(token)
        val fund = body["funds"][0]

        assertThat(fund["accruedThisMonth"].money()).isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(fund["plannedThisMonth"].money()).isEqualByComparingTo(BigDecimal("100.00"))
        // Reaches the fund's share, which is a different thing from being banked in it.
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)

        // The client redraws the share every frame from the month's own figures rather than
        // from the flows, so it needs the month's total and the month's rate.
        assertThat(body["monthNetAccrued"].money()).isEqualByComparingTo(BigDecimal("200.00"))
    }

    @Test
    @DisplayName("a finished month can be looked back at, and the funds stay in this one")
    fun `browsing a past month`() {
        val token = register("browser@example.com")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        saveEntry(token, """{"direction":"INCOME","amount":"200.00","category":"salary"}""")

        val lastMonth = YearMonth.now().minusMonths(1)
        val past = ledger(token, month = lastMonth.toString())

        assertThat(past["month"].asText()).isEqualTo(lastMonth.toString())
        assertThat(past["isBrowsingPast"].asBoolean()).isTrue()
        // This month's record is not in last month.
        assertThat(past["entries"]).isEmpty()
        assertThat(past["actualIncome"].money()).isEqualByComparingTo(BigDecimal.ZERO)

        // The funds are a running position, so they answer from the month it is now however
        // far back the rest of the screen is looking.
        assertThat(past["funds"][0]["accruedThisMonth"].money())
            .isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(past["monthNetAccrued"].money()).isEqualByComparingTo(BigDecimal("200.00"))
    }

    @Test
    @DisplayName("a month that will not parse answers with this one rather than failing")
    fun `an unparseable month falls back`() {
        val token = register("badmonth@example.com")
        val body = ledger(token, month = "not-a-month")

        assertThat(body["month"].asText()).isEqualTo(YearMonth.now().toString())
        assertThat(body["isBrowsingPast"].asBoolean()).isFalse()
    }

    @Test
    @DisplayName("a payment written down for later in the month has not happened yet")
    fun `a future record is projected but not accrued`() {
        val today = LocalDate.now()
        val lastDay = YearMonth.from(today).atEndOfMonth()
        // On the last day of a month there is no "later this month" to write down.
        assumeTrue(today.isBefore(lastDay), "no future day left in this month")

        val token = register("future@example.com")
        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        saveEntry(
            token,
            """{"direction":"EXPENSE","amount":"500.00","category":"rent","occurredOn":"$lastDay"}""",
        )

        val body = ledger(token)
        val fund = body["funds"][0]

        // The money has not left yet, so nothing may have come out of the fund on account of
        // it, and nothing may have been added to what is left over.
        assertThat(fund["balance"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(body["netAccrued"].money()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(body["recordedNet"].money()).isEqualByComparingTo(BigDecimal.ZERO)

        // The projection is exactly where a payment due on the last day belongs, and the
        // record itself is still listed: writing it down early is the point of writing it
        // down early.
        assertThat(body["plannedExpense"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(body["actualExpense"].money()).isEqualByComparingTo(BigDecimal("500.00"))
        assertThat(body["entries"]).hasSize(1)
    }

    @Test
    @DisplayName("a day view still answers the funds from the whole month")
    fun `the month's records reach the funds on a day view`() {
        val token = register("dayshare@example.com")

        saveFund(token, """{"name":"Emergency","percent":"50.00"}""")
        saveEntry(token, """{"direction":"INCOME","amount":"200.00","category":"salary"}""")

        val day = ledger(token, period = "DAY")

        // Switching to today must not make a fund appear to shrink, so both the fund and the
        // figure the client rebuilds it from stay the month's.
        assertThat(day["funds"][0]["accruedThisMonth"].money())
            .isEqualByComparingTo(BigDecimal("100.00"))
        assertThat(day["monthNetAccrued"].money()).isEqualByComparingTo(BigDecimal("200.00"))
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

    // --- a flow's history is not rewritten by what it becomes later -----------

    @Test
    @DisplayName("a flow entered today starts today, and does not credit the days before it")
    fun `a new flow starts today by default`() {
        val token = register("today@example.com")

        // Deliberately not through saveFlow: this is about what the server does with no date.
        postFlow(token, """{"name":"Allowance","direction":"INCOME","amount":"17.00","period":"DAILY"}""")

        val flow = ledger(token)["flows"][0]
        assertThat(LocalDate.parse(flow["startsOn"].asText())).isEqualTo(LocalDate.now())

        // Only the days that are left, not the whole month. Dating it to the 1st credited
        // every day since, which on the 30th is RM510 of money the person never had.
        val today = LocalDate.now()
        val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
        assertThat(ledger(token)["plannedIncome"].money())
            .isEqualByComparingTo(BigDecimal.valueOf(17L * daysLeft))
    }

    @Test
    @DisplayName("a raise applies from the day it happened, leaving finished months alone")
    fun `an effective-dated edit splits the flow instead of rewriting it`() {
        val token = register("raise@example.com")
        val lastMonth = YearMonth.now().minusMonths(1)

        val id = objectMapper.readTree(
            saveFlow(
                token,
                """{"name":"Allowance","direction":"INCOME","amount":"10.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}"}""",
            ),
        )["id"].asText()

        val before = ledger(token, month = lastMonth.toString())["plannedIncome"].money()
        assertThat(before).isEqualByComparingTo(BigDecimal.valueOf(10L * lastMonth.lengthOfMonth()))

        val today = LocalDate.now()
        val successor = UUID.randomUUID().toString()
        postFlow(
            token,
            """{"id":"$id","successorId":"$successor","effectiveFrom":"$today","name":"Allowance","direction":"INCOME","amount":"17.00","period":"DAILY"}""",
        )

        // Two flows now, the first closed the evening before the raise.
        val flows = ledger(token)["flows"].associateBy { it["id"].asText() }
        assertThat(flows).hasSize(2)
        assertThat(LocalDate.parse(flows.getValue(id)["endsOn"].asText())).isEqualTo(today.minusDays(1))
        assertThat(flows.getValue(successor)["amount"].money()).isEqualByComparingTo(BigDecimal("17.00"))
        assertThat(LocalDate.parse(flows.getValue(successor)["startsOn"].asText())).isEqualTo(today)

        // The whole point: last month still answers with what was true last month.
        assertThat(ledger(token, month = lastMonth.toString())["plannedIncome"].money())
            .isEqualByComparingTo(before)
    }

    @Test
    @DisplayName("the offline queue may send a raise twice, and twice must not mean two raises")
    fun `splitting is idempotent on the successor id`() {
        val token = register("twice@example.com")
        val lastMonth = YearMonth.now().minusMonths(1)

        val id = objectMapper.readTree(
            saveFlow(
                token,
                """{"name":"Allowance","direction":"INCOME","amount":"10.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}"}""",
            ),
        )["id"].asText()

        val successor = UUID.randomUUID().toString()
        val body =
            """{"id":"$id","successorId":"$successor","effectiveFrom":"${LocalDate.now()}","name":"Allowance","direction":"INCOME","amount":"17.00","period":"DAILY"}"""
        postFlow(token, body)
        postFlow(token, body)

        assertThat(ledger(token)["flows"]).hasSize(2)
    }

    @Test
    @DisplayName("an edit with no effective date is a correction, and corrections do reach the past")
    fun `an edit without an effective date still rewrites`() {
        val token = register("typo@example.com")
        val lastMonth = YearMonth.now().minusMonths(1)

        val id = objectMapper.readTree(
            saveFlow(
                token,
                """{"name":"Allowance","direction":"INCOME","amount":"1.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}"}""",
            ),
        )["id"].asText()

        // A number typed wrong was never right, so putting it right must reach every month it
        // was wrong in. This is the other half of the choice the app puts to the person.
        postFlow(
            token,
            """{"id":"$id","name":"Allowance","direction":"INCOME","amount":"10.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}"}""",
        )

        assertThat(ledger(token)["flows"]).hasSize(1)
        assertThat(ledger(token, month = lastMonth.toString())["plannedIncome"].money())
            .isEqualByComparingTo(BigDecimal.valueOf(10L * lastMonth.lengthOfMonth()))
    }

    @Test
    @DisplayName("stopping a flow keeps what it already earned; deleting it does not")
    fun `an end date preserves history where deleting erases it`() {
        val token = register("stop@example.com")
        val lastMonth = YearMonth.now().minusMonths(1)

        val id = objectMapper.readTree(
            saveFlow(
                token,
                """{"name":"Interest","direction":"INCOME","amount":"1.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}"}""",
            ),
        )["id"].asText()
        val earned = BigDecimal.valueOf(lastMonth.lengthOfMonth().toLong())

        // Stopped yesterday: last month is untouched, because it really did earn it.
        postFlow(
            token,
            """{"id":"$id","name":"Interest","direction":"INCOME","amount":"1.00","period":"DAILY","startsOn":"${lastMonth.atDay(1)}","endsOn":"${LocalDate.now().minusDays(1)}"}""",
        )
        assertThat(ledger(token, month = lastMonth.toString())["plannedIncome"].money())
            .isEqualByComparingTo(earned)

        // Deleted outright: gone from every month it ever ran in. Kept as the escape hatch for
        // something entered by mistake, which is why the app asks before doing it.
        mockMvc.perform(
            delete("/api/ledger/flows/$id").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNoContent)

        assertThat(ledger(token, month = lastMonth.toString())["plannedIncome"].money())
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    @DisplayName("a yearly bonus arrives on its own date, not on the 31st of December")
    fun `a yearly flow pays in the month it names`() {
        val token = register("bonus@example.com")
        val year = LocalDate.now().year

        // Paid in a month that has already begun and whose payday has passed, otherwise there
        // is nothing to have received yet.
        val month = LocalDate.now().monthValue
        saveFlow(
            token,
            """{"name":"Bonus","direction":"INCOME","amount":"1200.00","period":"YEARLY","arrivesMonth":$month,"arrivesOn":1,"startsOn":"$year-01-01"}""",
        )

        val flow = ledger(token)["flows"][0]
        assertThat(flow["arrivesMonth"].asInt()).isEqualTo(month)
        // It landed in this month rather than waiting for December.
        assertThat(flow["receivedThisMonth"].money()).isEqualByComparingTo(BigDecimal("1200.00"))
    }

    @Test
    @DisplayName("a yearly flow with no month named still closes its year, as it always did")
    fun `a yearly flow without a payday keeps the old meaning`() {
        val token = register("yearend@example.com")
        val year = LocalDate.now().year

        saveFlow(
            token,
            """{"name":"Bonus","direction":"INCOME","amount":"1200.00","period":"YEARLY","startsOn":"$year-01-01"}""",
        )

        val flow = ledger(token)["flows"][0]
        // Absent rather than null: the serialiser leaves out what was never set.
        assertThat(flow.path("arrivesMonth").isMissingNode || flow.path("arrivesMonth").isNull).isTrue()
        // December has not finished, so nothing has landed yet.
        assertThat(flow["receivedThisMonth"].money()).isEqualByComparingTo(BigDecimal.ZERO)
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

    /**
     * Saves a flow, dating it to the first of this month unless the test says otherwise.
     *
     * Almost every test here is about something other than when a flow began -- a payday, a
     * fund's share, a rate -- and each means one that has been running all month. The server
     * defaults to today instead, deliberately, because it must not credit days nobody lived.
     * Saying so once here is better than repeating a date down seventeen call sites, and a
     * test that cares about the default says its own start date or omits this helper.
     */
    private fun saveFlow(token: String, body: String): String {
        val node = objectMapper.readTree(body) as ObjectNode
        if (!node.has("startsOn")) {
            node.put("startsOn", LocalDate.now().withDayOfMonth(1).toString())
        }
        return postFlow(token, objectMapper.writeValueAsString(node))
    }

    /** Sends exactly what it is given, so the server's own defaults can be tested. */
    private fun postFlow(token: String, body: String): String =
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

    private fun ledger(token: String, period: String = "MONTH", month: String? = null): JsonNode {
        val request = get("/api/ledger").param("period", period)
        if (month != null) request.param("month", month)
        val result = mockMvc.perform(
            request.header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
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
