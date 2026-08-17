package com.dividendstream.app.data.remote

import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Runs an API call and converts anything that goes wrong into a message worth showing.
 *
 * The backend already returns a structured, user-safe error body; this reads it. Anything
 * unrecognised falls back to generic wording -- a raw exception message never reaches the UI.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (ex: HttpException) {
    AppResult.Failure(ex.toAppError(json))
} catch (ex: IOException) {
    // No connectivity, DNS failure, timeout, connection reset.
    AppResult.Failure(AppError.offline)
} catch (ex: Exception) {
    AppResult.Failure(
        AppError(
            code = "UNEXPECTED",
            message = "Something went wrong. Please try again.",
            isRetryable = true,
        ),
    )
}

private fun HttpException.toAppError(json: Json): AppError {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    val parsed = body
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString<ApiErrorDto>(it) }.getOrNull() }

    if (parsed != null) {
        return AppError(
            code = parsed.code,
            message = parsed.message,
            fieldErrors = parsed.fieldErrors.orEmpty(),
            isRetryable = code() >= 500,
        )
    }

    return when (code()) {
        401 -> AppError("UNAUTHENTICATED", "Please sign in to continue.")
        403 -> AppError("FORBIDDEN", "You do not have access to this.")
        404 -> AppError("NOT_FOUND", "We could not find what you were looking for.")
        429 -> AppError("RATE_LIMITED", "Too many attempts. Please try again shortly.", isRetryable = true)
        in 500..599 -> AppError("SERVER_ERROR", "The service is having trouble. Please try again.", isRetryable = true)
        else -> AppError("UNEXPECTED", "Something went wrong. Please try again.", isRetryable = true)
    }
}
