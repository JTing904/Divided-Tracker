@file:UseSerializers(BigDecimalSerializer::class, InstantSerializer::class, LocalDateSerializer::class)

package com.dividendstream.app.domain

import com.dividendstream.app.core.BigDecimalSerializer
import com.dividendstream.app.core.InstantSerializer
import com.dividendstream.app.core.LocalDateSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A ledger change made on the device and not yet accepted by the server.
 *
 * The server sleeps between uses and takes a minute or two to wake. Writing down a lunch is
 * something a person does standing at a counter, several times a day; making them watch a
 * spinner for it means they stop writing things down, and a ledger nobody keeps is worse than
 * one that is a minute behind.
 *
 * Every kind of change is here, not only the ones that felt slow. One path is easier to trust
 * than two, and the choice between them would have to be made by guessing whether the server
 * is awake -- which only the network knows, and only after trying.
 *
 * Each variant mirrors one repository call, and each carries the id the server will key on, so
 * sending it twice records it once. That was designed in from the first ledger commit; it is
 * what makes a queue safe here at all.
 */
@Serializable
sealed interface PendingLedgerWrite {

    /** The queue's own name for this change. Also the id the server is asked to key on. */
    val key: String
    val queuedAt: Instant

    /**
     * Set when the server refused in a way retrying cannot fix -- a percentage over what is
     * free, a malformed figure. Such a change stops being retried and waits to be seen: a
     * queue that keeps failing silently forever is worse than one that says so.
     */
    val failure: String?

    fun blocked(reason: String): PendingLedgerWrite
    fun unblocked(): PendingLedgerWrite

    val isBlocked: Boolean get() = failure != null

    /** What the change is, in the fewest words that still say which one it was. */
    val describe: String

    @Serializable
    @SerialName("flow")
    data class Flow(
        override val key: String,
        val name: String,
        val direction: String,
        val amount: BigDecimal,
        val period: String,
        val category: String?,
        val arrivesOn: Int?,
        val startsOn: LocalDate?,
        val endsOn: LocalDate?,
        override val queuedAt: Instant,
        override val failure: String? = null,
    ) : PendingLedgerWrite {
        override fun blocked(reason: String) = copy(failure = reason)
        override fun unblocked() = copy(failure = null)
        override val describe: String get() = name
    }

    @Serializable
    @SerialName("entry")
    data class Entry(
        override val key: String,
        val direction: String,
        val amount: BigDecimal,
        val occurredOn: LocalDate?,
        val category: String?,
        val note: String?,
        override val queuedAt: Instant,
        override val failure: String? = null,
    ) : PendingLedgerWrite {
        override fun blocked(reason: String) = copy(failure = reason)
        override fun unblocked() = copy(failure = null)
        override val describe: String get() = note ?: category ?: "Record"
    }

    @Serializable
    @SerialName("fund")
    data class Fund(
        override val key: String,
        val name: String,
        val percent: BigDecimal,
        val icon: String?,
        override val queuedAt: Instant,
        override val failure: String? = null,
    ) : PendingLedgerWrite {
        override fun blocked(reason: String) = copy(failure = reason)
        override fun unblocked() = copy(failure = null)
        override val describe: String get() = name
    }

    @Serializable
    @SerialName("movement")
    data class Movement(
        override val key: String,
        val fundId: String,
        val direction: String,
        val amount: BigDecimal,
        val occurredOn: LocalDate?,
        val note: String?,
        override val queuedAt: Instant,
        override val failure: String? = null,
    ) : PendingLedgerWrite {
        override fun blocked(reason: String) = copy(failure = reason)
        override fun unblocked() = copy(failure = null)
        override val describe: String get() = note ?: if (direction == "DEPOSIT") "Put in" else "Taken out"
    }

    /**
     * A deletion, of anything.
     *
     * [target] names which endpoint to call. A deletion is queued like everything else so that
     * removing something and then closing the app does not quietly bring it back.
     */
    @Serializable
    @SerialName("delete")
    data class Delete(
        override val key: String,
        val target: Target,
        val id: String,
        val label: String,
        override val queuedAt: Instant,
        override val failure: String? = null,
    ) : PendingLedgerWrite {
        override fun blocked(reason: String) = copy(failure = reason)
        override fun unblocked() = copy(failure = null)
        override val describe: String get() = "Remove $label"

        @Serializable
        enum class Target { FLOW, ENTRY, FUND, MOVEMENT }
    }
}
