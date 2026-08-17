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

@IntegrationTest
@Transactional
class AuthAndAuthorizationIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `rejects requests with no token`() {
        mockMvc.perform(get("/api/portfolio"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    @Test
    fun `distinguishes an invalid token from an expired session`() {
        mockMvc.perform(
            get("/api/portfolio").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
    }

    @Test
    fun `refuses a duplicate registration`() {
        register("taken@example.com")

        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Someone Else","email":"TAKEN@example.com","password":"another-password"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
    }

    @Test
    fun `reports validation problems per field without leaking internals`() {
        mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"","email":"not-an-email","password":"short"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.email").exists())
            .andExpect(jsonPath("$.fieldErrors.password").exists())
            .andExpect(jsonPath("$.fieldErrors.name").exists())
    }

    @Test
    fun `rejects a wrong password without revealing whether the account exists`() {
        register("real@example.com")

        val wrongPassword = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"real@example.com","password":"wrong-password"}"""),
        ).andExpect(status().isUnauthorized).andReturn()

        val unknownAccount = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"nobody@example.com","password":"wrong-password"}"""),
        ).andExpect(status().isUnauthorized).andReturn()

        // Identical responses, so the endpoint cannot be used to enumerate accounts.
        assertThat(objectMapper.readTree(wrongPassword.response.contentAsString)["code"].asText())
            .isEqualTo("INVALID_CREDENTIALS")
        assertThat(objectMapper.readTree(unknownAccount.response.contentAsString)["code"].asText())
            .isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `signs in with the registered credentials`() {
        register("signin@example.com")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"signin@example.com","password":"correct-horse-battery"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
            .andExpect(jsonPath("$.refreshToken").isNotEmpty)
            .andExpect(jsonPath("$.user.email").value("signin@example.com"))
    }

    @Test
    @DisplayName("a refresh token is single use")
    fun `rotates refresh tokens`() {
        val session = register("rotate@example.com")
        val originalRefresh = session["refreshToken"].asText()

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$originalRefresh"}"""),
        ).andExpect(status().isOk)

        // Replaying the token a second time must fail, so a stolen copy is worthless.
        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$originalRefresh"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"))
    }

    @Test
    fun `logout revokes every refresh token for the account`() {
        val session = register("logout@example.com")
        val token = session["accessToken"].asText()
        val refresh = session["refreshToken"].asText()

        mockMvc.perform(
            post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refresh"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("one user cannot reach another user's portfolio or dividends")
    fun `isolates users from each other`() {
        val alice = register("alice@example.com")["accessToken"].asText()
        val bob = register("bob@example.com")["accessToken"].asText()

        val aliceHolding = mockMvc.perform(
            post("/api/portfolio")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"symbol":"1155","quantity":"1000","averagePrice":"9.50"}"""),
        ).andExpect(status().isCreated).andReturn()

        val holdingId = objectMapper.readTree(aliceHolding.response.contentAsString)["id"].asText()

        val aliceDividends = mockMvc.perform(
            get("/api/dividends/live").header(HttpHeaders.AUTHORIZATION, "Bearer $alice"),
        ).andExpect(status().isOk).andReturn()
        val dividendId = objectMapper.readTree(aliceDividends.response.contentAsString)["streams"][0]["transactionId"].asText()

        // Bob's own portfolio is empty...
        mockMvc.perform(get("/api/portfolio").header(HttpHeaders.AUTHORIZATION, "Bearer $bob"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.holdings").isEmpty)

        // ...and Alice's ids are useless to him, even though they are valid ids.
        mockMvc.perform(
            put("/api/portfolio/$holdingId")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":"1","averagePrice":"1.00"}"""),
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            delete("/api/portfolio/$holdingId").header(HttpHeaders.AUTHORIZATION, "Bearer $bob"),
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/dividends/$dividendId").header(HttpHeaders.AUTHORIZATION, "Bearer $bob"),
        ).andExpect(status().isNotFound)

        // Alice still has everything.
        mockMvc.perform(get("/api/portfolio").header(HttpHeaders.AUTHORIZATION, "Bearer $alice"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.holdings.length()").value(1))
    }

    @Test
    fun `updates the signed-in user's profile`() {
        val token = register("profile@example.com")["accessToken"].asText()

        mockMvc.perform(
            put("/api/user/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed Investor","baseCurrency":"MYR"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed Investor"))

        mockMvc.perform(get("/api/user/profile").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("profile@example.com"))
    }

    private fun register(email: String): JsonNode {
        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Test Investor","email":"$email","password":"correct-horse-battery"}"""),
        ).andExpect(status().isCreated).andReturn()

        return objectMapper.readTree(result.response.contentAsString)
    }
}
