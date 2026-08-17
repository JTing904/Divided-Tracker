package com.dividendstream.api.user

import com.dividendstream.api.common.NotFoundException
import com.dividendstream.api.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/user")
class UserController(private val userRepository: UserRepository) {

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    fun profile(@AuthenticationPrincipal principal: AuthPrincipal): UserProfileResponse =
        loadUser(principal).toProfileResponse()

    @PutMapping("/profile")
    @Transactional
    fun updateProfile(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): UserProfileResponse {
        val user = loadUser(principal)
        user.name = request.name.trim()
        user.baseCurrency = request.baseCurrency.uppercase()
        return userRepository.save(user).toProfileResponse()
    }

    private fun loadUser(principal: AuthPrincipal): UserEntity =
        userRepository.findById(principal.userId)
            .orElseThrow { NotFoundException("Account not found.") }
}
