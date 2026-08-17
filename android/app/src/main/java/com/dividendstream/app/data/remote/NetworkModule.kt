package com.dividendstream.app.data.remote

import com.dividendstream.app.data.local.Session
import com.dividendstream.app.data.local.SessionStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Wires the HTTP stack. Deliberately plain: one client for normal traffic and a second,
 * interceptor-free client used only to refresh tokens, so a failing refresh cannot recurse
 * into itself.
 */
class NetworkModule(
    baseUrl: String,
    sessionStore: SessionStore,
    isDebug: Boolean,
) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val converterFactory = json.asConverterFactory("application/json".toMediaType())

    /** No auth, no authenticator: this exists purely to exchange a refresh token. */
    private val refreshApi: DividendStreamApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(converterFactory)
        .build()
        .create(DividendStreamApi::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(BearerTokenInterceptor(sessionStore))
        .authenticator(TokenRefreshAuthenticator(sessionStore, refreshApi))
        .apply {
            if (isDebug) {
                // BASIC, not BODY: response bodies contain access tokens.
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .build()

    val api: DividendStreamApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(converterFactory)
        .build()
        .create(DividendStreamApi::class.java)
}

private class BearerTokenInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = sessionStore.current?.accessToken
        val request = if (token == null) {
            chain.request()
        } else {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(request)
    }
}

/**
 * Refreshes the access token when the backend rejects one, then replays the request.
 *
 * Synchronised, and it re-checks the stored token first: when several requests fail at once
 * only the first performs the exchange, and the rest pick up its result. Without that, the
 * losing threads would present a refresh token the winner had already rotated away.
 */
private class TokenRefreshAuthenticator(
    private val sessionStore: SessionStore,
    private val refreshApi: DividendStreamApi,
) : Authenticator {

    @Synchronized
    override fun authenticate(route: Route?, response: Response): Request? {
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: return null // The request was unauthenticated; nothing to refresh.

        if (priorResponseCount(response) >= MAX_ATTEMPTS) return null

        val session = sessionStore.current ?: return null

        // Another thread already refreshed while this request was in flight.
        if (session.accessToken != failedToken) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${session.accessToken}")
                .build()
        }

        val refreshed = runBlocking {
            runCatching { refreshApi.refresh(RefreshRequest(session.refreshToken)) }.getOrNull()
        }

        if (refreshed == null) {
            // The refresh token is spent or revoked: the session is genuinely over.
            runBlocking { sessionStore.clear() }
            return null
        }

        runBlocking {
            sessionStore.save(
                Session(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken,
                    userId = refreshed.user.id,
                    userName = refreshed.user.name,
                    userEmail = refreshed.user.email,
                    baseCurrency = refreshed.user.baseCurrency,
                ),
            )
        }

        return response.request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.accessToken}")
            .build()
    }

    private fun priorResponseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
