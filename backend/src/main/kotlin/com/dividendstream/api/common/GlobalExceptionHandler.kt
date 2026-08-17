package com.dividendstream.api.common

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Converts every failure into [ApiErrorResponse]. Unexpected exceptions are logged in full
 * server-side but reported to the client as a generic message, so stack traces, SQL and
 * class names never leave the process.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(ex.status).body(ApiErrorResponse(ex.code, ex.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate { field ->
            field.field to (field.defaultMessage ?: "is invalid")
        }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "VALIDATION_FAILED",
                message = "Please check the highlighted fields.",
                fieldErrors = fieldErrors,
            ),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun handleMalformedRequest(ex: Exception): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse("MALFORMED_REQUEST", "The request could not be understood."),
        )

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse("NOT_FOUND", "The requested resource was not found."),
        )

    /**
     * A unique-constraint race that slipped past the service-level check. The message is
     * intentionally generic: constraint names are internal detail.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiErrorResponse> {
        log.warn("Data integrity violation", ex)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse("CONFLICT", "That change conflicts with existing data."),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse("INTERNAL_ERROR", "Something went wrong. Please try again."),
        )
    }
}
