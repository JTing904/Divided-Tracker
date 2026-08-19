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
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.InterruptedIOException
import java.time.Instant
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
    /** Told what the network is seeing, so screens can explain a wait instead of spinning. */
    private val availability: ServerAvailability = ServerAvailability(),
) {

    val serverAvailability: ServerAvailability get() = availability

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
                // No ColdStartInterceptor: refreshing is a POST, and a repeat would present a
                // refresh token the first attempt may already have rotated away. It gets a
                // long timeout instead, because it is the gate to every other call and a
                // sleeping server must not be allowed to end the session.
                .readTimeout(120, TimeUnit.SECONDS)
                .build(),
        )
        .addConverterFactory(converterFactory)
        .build()
        .create(DividendStreamApi::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(ColdStartInterceptor(availability))
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

/**
 * Gives a sleeping server a second, more patient chance.
 *
 * Free hosting tiers stop the container after a spell of no traffic, so the request that
 * wakes one waits for a whole application boot rather than for a database query. Twenty
 * seconds is generous for a server already running and hopeless for one that is starting.
 * Rather than make every request wait the worst case, the first timeout is read as a signal
 * and the call repeated once on a longer budget.
 *
 * **Only idempotent methods are retried.** A POST that timed out may still have been received
 * and acted on, and replaying "add this holding" would leave two. Those surface as
 * [com.dividendstream.app.core.AppError.serverWaking] for the user to repeat deliberately --
 * and by then the failed attempt has already started the container.
 *
 * This does not pretend to cover a cold start of any length; a boot slower than the budget
 * below still fails. It fails having said something true, which is the part that matters.
 */
private class ColdStartInterceptor(private val availability: ServerAvailability) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return try {
            chain.proceed(request).also { availability.reportAwake() }
        } catch (timeout: InterruptedIOException) {
            // Reaching the host and getting no answer is what a sleeping container looks like.
            // Recorded before the retry, so a screen can start explaining the wait immediately
            // rather than only once the second attempt has also run out.
            availability.reportWaking(Instant.now())

            if (request.method !in IDEMPOTENT) throw timeout

            chain.withReadTimeout(COLD_START_READ_SECONDS, TimeUnit.SECONDS)
                .proceed(request)
                .also { availability.reportAwake() }
        }
    }

    private companion object {
        val IDEMPOTENT = setOf("GET", "HEAD")

        /**
         * Sized from a measurement, not a guess.
         *
         * A timed cold wake of this project's own free-tier deployment took 104 seconds end to
         * end: about 8 for the host to start the container, 85 for the JVM and Spring context,
         * and 11 more for the servlet to initialise on that first request. The first attempt
         * has already spent the client's twenty-second read timeout, so this covers the rest
         * with margin for a slower day, without waiting forever on a server that is simply gone.
         */
        const val COLD_START_READ_SECONDS = 120
    }
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

        val outcome = runBlocking {
            runCatching { refreshApi.refresh(RefreshRequest(session.refreshToken)) }
        }
        val refreshed = outcome.getOrNull()

        if (refreshed == null) {
            // Two very different failures used to land here together. Only the server saying
            // no means the session is over; a timeout means the server was asleep, and signing
            // someone out for that would end their session every time the host idled -- which
            // on free hosting is every time they put the phone down.
            val cause = outcome.exceptionOrNull()
            val rejected = cause is HttpException && cause.code() in 400..499
            if (rejected) runBlocking { sessionStore.clear() }
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
