package com.dividendstream.api.common

import org.springframework.http.HttpStatus

/**
 * Errors that are safe to describe to a client. Anything not modelled here surfaces as a
 * generic 500 so that internal detail never reaches the app.
 */
sealed class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class NotFoundException(message: String) :
    ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

class ConflictException(code: String, message: String) :
    ApiException(HttpStatus.CONFLICT, code, message)

class InvalidRequestException(message: String, code: String = "INVALID_REQUEST") :
    ApiException(HttpStatus.BAD_REQUEST, code, message)

class UnauthorizedException(message: String, code: String = "UNAUTHORIZED") :
    ApiException(HttpStatus.UNAUTHORIZED, code, message)

class ForbiddenException(
    message: String = "You do not have access to this resource.",
    code: String = "FORBIDDEN",
) : ApiException(HttpStatus.FORBIDDEN, code, message)

class UpstreamUnavailableException(message: String = "Market data is temporarily unavailable.") :
    ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", message)

class RateLimitedException(message: String = "Too many requests. Please try again shortly.") :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message)
