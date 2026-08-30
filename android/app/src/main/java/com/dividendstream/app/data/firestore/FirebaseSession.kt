package com.dividendstream.app.data.firestore

import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.repository.GoalPeriod
import com.dividendstream.app.data.repository.IncomeGoal
import com.dividendstream.app.data.repository.IncomeGoalStore
import com.dividendstream.app.data.repository.SessionMirror
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.time.Instant

/** Who is signed in, from Firebase's point of view. */
data class FirebaseAccount(
    val uid: String,
    val name: String,
    val email: String,
    val baseCurrency: String = "MYR",
)

/**
 * Keeps a monthly income goal on the person's own profile.
 *
 * Separate from the session repository so the dashboard depends on the goal and not on
 * everything else Firebase can do.
 */
class FirestoreIncomeGoals(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : IncomeGoalStore {

    override fun goal(): Flow<IncomeGoal?> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        // On the profile document rather than in a collection of its own: it is one figure
        // about the person, and a collection holding exactly one document forever only ever
        // costs a reader time.
        val registration = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, _ ->
                // A goal saved before periods existed was a monthly one, and is still read as
                // one. Dropping it would have quietly cleared a target somebody had set.
                val amount = snapshot?.getString("incomeGoal")
                    ?: snapshot?.getString("monthlyIncomeGoal")
                val period = snapshot?.getString("incomeGoalPeriod")
                trySend(
                    amount
                        ?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() }
                        ?.let {
                            IncomeGoal(
                                it,
                                runCatching { GoalPeriod.valueOf(period ?: "MONTH") }
                                    .getOrDefault(GoalPeriod.MONTH),
                            )
                        },
                )
            }
        awaitClose { registration.remove() }
    }

    /** Written as a string, like every other amount here. Null clears it. */
    override suspend fun set(goal: IncomeGoal?) {
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid)
            .set(
                mapOf(
                    "incomeGoal" to goal?.amount?.toPlainString(),
                    "incomeGoalPeriod" to goal?.period?.name,
                    // Cleared, so an old client reading only this field is not left showing a
                    // goal that has since been changed or removed.
                    "monthlyIncomeGoal" to null,
                ),
                SetOptions.merge(),
            )
            .await()
    }
}

/**
 * Signing in, without a server of ours in the middle.
 *
 * Firebase keeps the session itself, on the device, and renews the token in the background.
 * That quietly removes a whole class of failure this app had: the token expired while the
 * phone was idle, the refresh went to a server that had gone to sleep, and a person was told
 * the thing they had just written down could not be saved. There is nothing here to be asleep.
 *
 * Errors are translated into the app's own [AppError] rather than surfaced as Firebase
 * exceptions, so the screens above carry on saying what they said before -- and so that
 * "your password is wrong" is never reported as something a retry could fix.
 */
class FirebaseSessionRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : SessionMirror {

    /** The signed-in account, or null. Emits again whenever that changes. */
    val accounts: Flow<FirebaseAccount?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAccount()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    /** Available synchronously, because a Firestore query needs the uid before it can start. */
    val current: FirebaseAccount? get() = auth.currentUser?.toAccount()

    suspend fun register(name: String, email: String, password: String): AppResult<FirebaseAccount> =
        attempt {
            val created = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = requireNotNull(created.user) { "Firebase created no user" }
            // The display name lives on the Firebase account so it survives a reinstall; the
            // profile document holds what Firebase has no field for.
            user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
            writeProfile(user.uid, name.trim(), email.trim())
            FirebaseAccount(user.uid, name.trim(), email.trim())
        }

    suspend fun login(email: String, password: String): AppResult<FirebaseAccount> = attempt {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        requireNotNull(result.user).toAccount().also { writeProfile(it.uid, it.name, it.email) }
    }

    /** Takes the Google ID token the Credential Manager already fetches for the old backend. */
    suspend fun googleSignIn(idToken: String): AppResult<FirebaseAccount> = attempt {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        requireNotNull(result.user).toAccount().also { writeProfile(it.uid, it.name, it.email) }
    }

    fun logout() = auth.signOut()

    // --- the neutral seam the sign-in screens use -----------------------------

    override suspend fun signIn(email: String, password: String): String? =
        (login(email, password) as? AppResult.Failure)?.error?.code

    override suspend fun createAccount(name: String, email: String, password: String): String? =
        (register(name, email, password) as? AppResult.Failure)?.error?.code

    override suspend fun signInWithGoogle(idToken: String): String? =
        (googleSignIn(idToken) as? AppResult.Failure)?.error?.code

    /**
     * Writes the profile, leaving alone anything already there.
     *
     * Merged rather than set, and this matters on every sign-in after the first: replacing the
     * document would put baseCurrency back to its default and quietly re-denominate somebody's
     * whole ledger.
     */
    private suspend fun writeProfile(uid: String, name: String, email: String) {
        val document = mutableMapOf<String, Any?>(
            "displayName" to name,
            "email" to email,
            "createdAt" to Instant.now().toString(),
        )
        val existing = firestore.collection("users").document(uid).get().await()
        if (!existing.exists()) document["baseCurrency"] = "MYR"
        firestore.collection("users").document(uid)
            .set(document.filterValues { it != null }, SetOptions.merge()).await()
    }

    private fun FirebaseUser.toAccount() = FirebaseAccount(
        uid = uid,
        name = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@').orEmpty(),
        email = email.orEmpty(),
    )

    /**
     * Turns whatever Firebase threw into something a screen can act on.
     *
     * The retryable flag is the part that carries weight: it decides whether a failure is
     * offered a "try again" button, and whether a queued write is held or given up on. A wrong
     * password is not retryable however many times it is sent.
     */
    private inline fun <T> attempt(block: () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (ex: FirebaseAuthInvalidCredentialsException) {
        AppResult.Failure(AppError("INVALID_CREDENTIALS", "That email and password do not match."))
    } catch (ex: FirebaseAuthInvalidUserException) {
        AppResult.Failure(AppError("NO_SUCH_USER", "There is no account with that email."))
    } catch (ex: FirebaseAuthUserCollisionException) {
        AppResult.Failure(AppError("EMAIL_TAKEN", "That email already has an account. Sign in instead."))
    } catch (ex: FirebaseAuthWeakPasswordException) {
        AppResult.Failure(AppError("WEAK_PASSWORD", "Choose a longer password -- at least six characters."))
    } catch (ex: IOException) {
        AppResult.Failure(AppError.offline)
    } catch (ex: Exception) {
        AppResult.Failure(
            AppError("SIGN_IN_FAILED", ex.message ?: "Could not sign in. Please try again.", isRetryable = true),
        )
    }
}
