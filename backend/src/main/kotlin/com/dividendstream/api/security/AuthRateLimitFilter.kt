package com.dividendstream.api.security

import com.dividendstream.api.common.ApiErrorResponse
import com.dividendstream.api.config.RateLimitProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed-window per-IP throttle on the unauthenticated auth endpoints, which are the ones
 * worth brute-forcing.
 *
 * In-memory by design: a single instance needs no external dependency. Behind more than one
 * instance this becomes per-instance, and the counter should move to Redis (the same
 * `INCR key` + `EXPIRE` shape) -- see docs/ARCHITECTURE.md.
 */
@Component
class AuthRateLimitFilter(
    private val properties: RateLimitProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private class Window(val minute: Long, var hits: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/auth/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val minute = Instant.now().epochSecond / 60
        val key = request.remoteAddr ?: "unknown"

        val window = windows.compute(key) { _, existing ->
            if (existing == null || existing.minute != minute) {
                Window(minute, 1)
            } else {
                existing.hits += 1
                existing
            }
        }!!

        if (windows.size > MAX_TRACKED_CLIENTS) {
            windows.entries.removeIf { it.value.minute < minute }
        }

        if (window.hits > properties.authRequestsPerMinute) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.setHeader("Retry-After", "60")
            objectMapper.writeValue(
                response.outputStream,
                ApiErrorResponse("RATE_LIMITED", "Too many attempts. Please try again in a minute."),
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private companion object {
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
