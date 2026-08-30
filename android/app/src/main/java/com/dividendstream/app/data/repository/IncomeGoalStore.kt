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
    fun goal(): Flow<IncomeGoal?>

    suspend fun set(goal: IncomeGoal?)
}

/**
 * What somebody is aiming to earn, and over what stretch of time.
 *
 * The period is stored rather than normalised away. "RM20 a day" and "RM609 a month" are the
 * same rate and not the same thought, and converting one into the other to store it means
 * showing somebody a number they never typed -- with a rounding error, since months are not
 * all the same length.
 */
data class IncomeGoal(val amount: BigDecimal, val period: GoalPeriod)

enum class GoalPeriod { DAY, MONTH, YEAR }
