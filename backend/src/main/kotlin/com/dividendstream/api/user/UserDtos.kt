package com.dividendstream.api.user

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class UserProfileResponse(
    val id: UUID,
    val name: String,
    val email: String,
    val baseCurrency: String,
    val createdAt: Instant,
)

data class UpdateProfileRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(max = 120, message = "Name is too long")
    val name: String,

    @field:Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter code")
    val baseCurrency: String = "MYR",
)

fun UserEntity.toProfileResponse() = UserProfileResponse(
    id = id,
    name = name,
    email = email,
    baseCurrency = baseCurrency,
    createdAt = createdAt,
)
