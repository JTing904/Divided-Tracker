package com.dividendstream.app.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dividendstream.app.data.remote.GoogleAuthAttempt
import com.dividendstream.app.data.remote.GoogleConfigDto
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@Composable
fun rememberGoogleSignInLauncher(): GoogleSignInLauncher = remember { LoopbackGoogleSignIn() }

/**
 * The installed-application OAuth flow, as Google specifies it for desktop programs.
 *
 * A local HTTP server is started on a port the operating system picks, the system browser is
 * sent to Google's consent page, and the authorisation code comes back to that loopback
 * address. The code is then handed to *our* backend rather than redeemed here: redeeming needs
 * the client secret, and a secret inside a downloadable binary belongs to whoever downloads it.
 *
 * PKCE is not optional in this flow. The redirect lands on 127.0.0.1, where any other program
 * on the machine may also be listening; the verifier is what makes an intercepted code useless
 * on its own, and the state is what stops a code planted by one from being accepted here.
 */
private class LoopbackGoogleSignIn : GoogleSignInLauncher {

    override val isSupported: Boolean =
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

    override suspend fun launch(config: GoogleConfigDto): GoogleAuthAttempt? {
        val clientId = config.desktopClientId
        if (!config.desktopEnabled || clientId.isNullOrBlank()) {
            throw GoogleSignInFailed("Google sign-in is not set up on this server.")
        }
        if (!isSupported) {
            throw GoogleSignInFailed("This computer could not open a browser for Google sign-in.")
        }

        val verifier = randomUrlSafe(64)
        val challenge = sha256UrlSafe(verifier)
        val state = randomUrlSafe(24)

        return withContext(Dispatchers.IO) {
            // Port 0 lets the operating system choose a free one. Google allows any port on the
            // loopback address for installed apps, so nothing has to be registered in advance.
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val redirectUri = "http://127.0.0.1:" + server.address.port + "/callback"
            val results = ArrayBlockingQueue<Result<String?>>(1)

            server.createContext("/callback") { exchange ->
                val params = parseQuery(exchange.requestURI.rawQuery)
                val outcome: Result<String?> = when {
                    !constantTimeEquals(params["state"], state) ->
                        Result.failure(GoogleSignInFailed("That Google sign-in did not match this attempt."))

                    params["error"] == "access_denied" -> Result.success(null)

                    params["error"] != null ->
                        Result.failure(GoogleSignInFailed("Google refused the sign-in. Please try again."))

                    params["code"] != null -> Result.success(params["code"])

                    else -> Result.failure(GoogleSignInFailed("Google's reply carried no sign-in code."))
                }

                val body = closingPage(outcome).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                results.offer(outcome)
            }

            server.start()
            try {
                Desktop.getDesktop().browse(URI(authorizationUrl(clientId, redirectUri, challenge, state)))

                // Bounded, because a browser tab the user simply abandons would otherwise leave
                // this waiting and hold the port for the life of the process.
                val outcome = results.poll(SIGN_IN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                    ?: throw GoogleSignInFailed("Google sign-in timed out. Please try again.")

                val code = outcome.getOrThrow()
                if (code == null) {
                    null
                } else {
                    GoogleAuthAttempt.AuthorizationCode(
                        code = code,
                        codeVerifier = verifier,
                        redirectUri = redirectUri,
                    )
                }
            } finally {
                server.stop(0)
            }
        }
    }

    private fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
    ): String {
        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            // Only what is needed to identify the person. No Drive, no Gmail, nothing this
            // application has any business reading.
            "scope" to "openid email profile",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            // Without this Google may silently reuse one account, which is wrong on a computer
            // more than one person signs in on.
            "prompt" to "select_account",
        )
        val query = params.entries.joinToString("&") { entry ->
            entry.key + "=" + URLEncoder.encode(entry.value, Charsets.UTF_8)
        }
        return "https://accounts.google.com/o/oauth2/v2/auth?" + query
    }

    /** What the browser tab is left showing once the flow is over. */
    private fun closingPage(outcome: Result<String?>): String {
        val signedIn = outcome.isSuccess && outcome.getOrNull() != null
        val heading = when {
            outcome.isFailure -> "Sign-in failed"
            signedIn -> "Signed in"
            else -> "Sign-in cancelled"
        }
        val detail = if (signedIn) {
            "You can close this tab and return to Dividend Stream."
        } else {
            "You can close this tab and try again in Dividend Stream."
        }
        return "<!doctype html><meta charset=\"utf-8\"><title>Dividend Stream</title>" +
            "<body style=\"font-family:system-ui,sans-serif;background:#0f1115;color:#e6e8ec;" +
            "display:grid;place-items:center;height:100vh;margin:0\">" +
            "<div style=\"text-align:center\"><h1 style=\"font-weight:600;font-size:1.25rem\">" +
            heading + "</h1><p style=\"color:#9aa3b2\">" + detail + "</p></div></body>"
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split("&")
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    URLDecoder.decode(part.substring(0, index), Charsets.UTF_8) to
                        URLDecoder.decode(part.substring(index + 1), Charsets.UTF_8)
                }
            }
            .toMap()

    private fun constantTimeEquals(supplied: String?, expected: String): Boolean =
        MessageDigest.isEqual(
            supplied.orEmpty().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun sha256UrlSafe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val SIGN_IN_TIMEOUT_MINUTES = 5L
    }
}
