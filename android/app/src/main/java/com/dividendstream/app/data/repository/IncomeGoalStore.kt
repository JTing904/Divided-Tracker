package com.dividendstream.app.data.repository

import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/**
 * A monthly income goal, and somewhere to keep it.
 *
 * An interface because the dashboard is compiled into the desktop too, and the store behind
 * this on the phone is Firestore, whose SDK is Android-only. The desktop passes null and shows
 * no goal rather than a broken one.
 */
interface IncomeGoalStore {

    /** Null while none has been set. Emits again when it changes, from any device. */
    fun goal(): Flow<BigDecimal?>

    suspend fun set(monthly: BigDecimal?)
}
