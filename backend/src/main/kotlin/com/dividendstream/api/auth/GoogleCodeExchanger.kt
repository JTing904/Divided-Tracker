package com.dividendstream.api.auth

import com.dividendstream.api.common.UnauthorizedException
import com.dividendstream.api.config.GoogleProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Redeems a desktop authorisation code with Google, and hands back the ID token inside.
 *
 * This exists so the desktop application never holds the client secret. An installed binary
 * cannot keep one -- anybody with the file has it -- and this project's rule is that
 * third-party credentials stay on the server. So the desktop performs the part that needs a
 * browser and a human, and posts the resulting code here; the exchange happens on this side.
 *
 * PKCE is still required of the caller. The code travels back through a loopback redirect on
 * the user's own machine, where another local program could race to read it; without the
 * verifier, a stolen code would be enough.
 */
@Component
class GoogleCodeExchanger(
    private val properties: GoogleProperties,
    restClientBuilder: RestClient.Builder,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = restClientBuilder.build()

    fun exchangeForIdToken(code: String, codeVerifier: String, redirectUri: String): String {
        if (!properties.isDesktopConfigured) {
            throw UnauthorizedException(
                "Google sign-in is not available on this server.",
                "GOOGLE_SIGN_IN_UNAVAILABLE",
            )
        }

        val form = LinkedMultiValueMap<String, String>().apply {
            add("code", code)
            add("client_id", properties.desktopClientId)
            add("client_secret", properties.desktopClientSecret)
            add("code_verifier", codeVerifier)
            add("redirect_uri", redirectUri)
            add("grant_type", "authorization_code")
        }

        val response = try {
            restClient.post()
                .uri(properties.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse::class.java)
        } catch (ex: RestClientException) {
            // Google's body explains the refusal in terms of our client configuration, not the
            // user's. Logged for whoever runs this; never returned.
            log.warn("Google rejected a desktop authorisation code", ex)
            null
        }

        return response?.idToken
            ?: throw UnauthorizedException(
                "That Google sign-in could not be completed. Please try again.",
                "GOOGLE_CODE_INVALID",
            )
    }

    /** Only the one field matters here; the access and refresh tokens are Google's to keep. */
    data class TokenResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("id_token") val idToken: String? = null,
    )
}
