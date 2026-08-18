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

    /**
     * Null for an account created through Google, which has no password to store. A database
     * check constraint keeps at least one sign-in method present.
     */
    @Column(name = "password_hash", length = 255)
    var passwordHash: String? = null,

    /**
     * Google's `sub` claim. Stable for the life of the Google account and, unlike an email
     * address, never reassigned to someone else -- so this is what identifies a returning
     * user, and the email is only ever used to link an account the person already had.
     */
    @Column(name = "google_subject", length = 255)
    var googleSubject: String? = null,

    @Column(name = "base_currency", nullable = false, length = 3)
    var baseCurrency: String = "MYR",
) : AuditableEntity()
