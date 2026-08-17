package com.dividendstream.app.core

/**
 * A user-presentable failure. Nothing here is derived from a stack trace or a server
 * exception message -- [message] is always text that is safe and sensible to show.
 */
data class AppError(
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    /** True when trying again could plausibly succeed (network blip, 5xx, timeout). */
    val isRetryable: Boolean = false,
) {
    val isAuthFailure: Boolean
        get() = code in setOf("UNAUTHENTICATED", "TOKEN_EXPIRED", "TOKEN_INVALID", "REFRESH_TOKEN_INVALID")

    companion object {
        val offline = AppError(
            code = "OFFLINE",
            message = "You are offline. Showing the last saved data.",
            isRetryable = true,
        )
    }
}

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

fun <T> AppResult<T>.dataOrNull(): T? = (this as? AppResult.Success)?.data

fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error
