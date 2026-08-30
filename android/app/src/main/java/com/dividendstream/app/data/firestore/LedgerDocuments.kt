package com.dividendstream.app.data.firestore

import com.dividendstream.app.domain.StoredEntry
import com.dividendstream.app.domain.StoredFlow
import com.dividendstream.app.domain.StoredFund
import com.dividendstream.app.domain.StoredMovement
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * How a stored record becomes a Firestore document, and back.
 *
 * Money crosses this boundary as a **string**, always. Firestore's number type is an IEEE
 * double, and RM0.29 is not representable in one: a ledger that stores money as a Firestore
 * number is a ledger that will eventually be a cent out and unable to say where. Strings go in
 * and BigDecimal comes back, which is the same bargain the JSON API already made -- and the
 * security rules refuse a write that tries anything else, because with no server left there is
 * nothing else that could.
 *
 * Dates are ISO strings for the same reason in a different key: a Firestore timestamp is an
 * instant, and `2026-08-30` is not an instant. Storing a date as one asks the reader to pick a
 * timezone to convert it back with, and any answer but the writer's original is a different
 * day.
 *
 * Reading is deliberately forgiving. A document with a field missing yields a default rather
 * than an exception, because a single unreadable row must not take a person's whole ledger
 * off the screen.
 */
internal object LedgerDocuments {

    // --- what repeats ---------------------------------------------------------

    fun flow(id: String, data: Map<String, Any?>) = StoredFlow(
        id = id,
        name = data.string("name").orEmpty(),
        direction = data.string("direction") ?: "EXPENSE",
        amount = data.money("amount"),
        period = data.string("period") ?: "MONTHLY",
        category = data.string("category"),
        currency = data.string("currency") ?: "MYR",
        arrivesOn = data.int("arrivesOn"),
        arrivesMonth = data.int("arrivesMonth"),
        startsOn = data.date("startsOn") ?: LocalDate.EPOCH,
        endsOn = data.date("endsOn"),
    )

    fun document(flow: StoredFlow): Map<String, Any?> = mapOf(
        "name" to flow.name,
        "direction" to flow.direction,
        "amount" to flow.amount.toPlainString(),
        "period" to flow.period,
        "category" to flow.category,
        "currency" to flow.currency,
        "arrivesOn" to flow.arrivesOn?.toLong(),
        "arrivesMonth" to flow.arrivesMonth?.toLong(),
        "startsOn" to flow.startsOn.toString(),
        "endsOn" to flow.endsOn?.toString(),
    )

    // --- what happened once ---------------------------------------------------

    fun entry(id: String, data: Map<String, Any?>) = StoredEntry(
        id = id,
        occurredOn = data.date("occurredOn") ?: LocalDate.EPOCH,
        direction = data.string("direction") ?: "EXPENSE",
        amount = data.money("amount"),
        category = data.string("category"),
        note = data.string("note"),
    )

    fun document(entry: StoredEntry): Map<String, Any?> = mapOf(
        "occurredOn" to entry.occurredOn.toString(),
        "direction" to entry.direction,
        "amount" to entry.amount.toPlainString(),
        "category" to entry.category,
        "note" to entry.note,
    )

    // --- where it goes --------------------------------------------------------

    fun fund(id: String, data: Map<String, Any?>) = StoredFund(
        id = id,
        name = data.string("name").orEmpty(),
        percent = data.money("percent"),
        icon = data.string("icon"),
        position = data.int("position") ?: 0,
        // Settlement walks from the month a fund was made in, so a missing createdAt would
        // silently bank nothing. The epoch is wrong but visible; a null would be neither.
        createdAt = data.instant("createdAt") ?: Instant.EPOCH,
    )

    fun document(fund: StoredFund): Map<String, Any?> = mapOf(
        "name" to fund.name,
        "percent" to fund.percent.toPlainString(),
        "icon" to fund.icon,
        "position" to fund.position.toLong(),
        "createdAt" to fund.createdAt.toString(),
    )

    // --- money moved into and out of a fund -----------------------------------

    fun movement(id: String, data: Map<String, Any?>) = StoredMovement(
        id = id,
        fundId = data.string("fundId").orEmpty(),
        occurredOn = data.date("occurredOn") ?: LocalDate.EPOCH,
        direction = data.string("direction") ?: StoredMovement.DEPOSIT,
        amount = data.money("amount"),
        note = data.string("note"),
        source = data.string("source") ?: StoredMovement.HAND,
        settledMonth = data.string("settledMonth"),
    )

    fun document(movement: StoredMovement): Map<String, Any?> = mapOf(
        "fundId" to movement.fundId,
        "occurredOn" to movement.occurredOn.toString(),
        "direction" to movement.direction,
        "amount" to movement.amount.toPlainString(),
        "note" to movement.note,
        "source" to movement.source,
        "settledMonth" to movement.settledMonth,
    )

    // --- reading a field without letting one bad row take the screen down -----

    private fun Map<String, Any?>.string(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    /**
     * A money field, as a string.
     *
     * A number is accepted rather than refused, because a document written by something other
     * than this app is still that person's data and refusing to show it helps nobody. It is
     * converted through its decimal text, never through `toDouble`, so nothing new is lost --
     * although whatever wrote it may already have.
     */
    private fun Map<String, Any?>.money(key: String): BigDecimal = when (val raw = this[key]) {
        is String -> runCatching { BigDecimal(raw) }.getOrDefault(BigDecimal.ZERO)
        is Number -> runCatching { BigDecimal(raw.toString()) }.getOrDefault(BigDecimal.ZERO)
        else -> BigDecimal.ZERO
    }

    private fun Map<String, Any?>.int(key: String): Int? = when (val raw = this[key]) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.date(key: String): LocalDate? =
        string(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun Map<String, Any?>.instant(key: String): Instant? =
        string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
