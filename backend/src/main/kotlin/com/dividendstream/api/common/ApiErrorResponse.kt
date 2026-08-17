package com.dividendstream.api.common

import java.time.Instant

/**
 * The single error shape every failing endpoint returns. [code] is stable and meant for
 * the client to branch on; [message] is user-presentable text.
 */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String>? = null,
    val timestamp: Instant = Instant.now(),
)
