package com.dividendstream.api.security

import com.dividendstream.api.common.ApiErrorResponse
import com.dividendstream.api.common.UnauthorizedException
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No credentials presented. Public routes proceed; protected ones are rejected
            // later by the authorization rules.
            filterChain.doFilter(request, response)
            return
        }

        val principal = try {
            jwtService.authenticate(header.removePrefix(BEARER_PREFIX).trim())
        } catch (ex: UnauthorizedException) {
            // Answer here rather than clearing the header silently, so the client can tell
            // TOKEN_EXPIRED (refresh) apart from TOKEN_INVALID (sign out).
            writeError(response, ex)
            return
        }

        val authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        authentication.details = WebAuthenticationDetailsSource().buildDetails(request)
        SecurityContextHolder.getContext().authentication = authentication

        filterChain.doFilter(request, response)
    }

    private fun writeError(response: HttpServletResponse, ex: UnauthorizedException) {
        SecurityContextHolder.clearContext()
        response.status = ex.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, ApiErrorResponse(ex.code, ex.message))
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
