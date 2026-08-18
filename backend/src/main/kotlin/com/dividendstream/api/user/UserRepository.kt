package com.dividendstream.api.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID> {

    @Query("SELECT u FROM UserEntity u WHERE lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(@Param("email") email: String): Optional<UserEntity>

    @Query("SELECT COUNT(u) > 0 FROM UserEntity u WHERE lower(u.email) = lower(:email)")
    fun existsByEmailIgnoreCase(@Param("email") email: String): Boolean

    fun findByGoogleSubject(googleSubject: String): Optional<UserEntity>
}
