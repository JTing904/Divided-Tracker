package com.dividendstream.api.user

import com.dividendstream.api.common.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false, length = 120)
    var name: String = "",

    /** Stored lower-cased; the unique index is on `lower(email)`. */
    @Column(name = "email", nullable = false, length = 255)
    var email: String = "",

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String = "",

    @Column(name = "base_currency", nullable = false, length = 3)
    var baseCurrency: String = "MYR",
) : AuditableEntity()
