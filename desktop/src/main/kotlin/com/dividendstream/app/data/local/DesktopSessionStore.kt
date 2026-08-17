package com.dividendstream.app.data.local

import com.dividendstream.app.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The signed-in session. Same shape as the Android type of this name; the two are compiled
 * into different applications and never meet.
 */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val userName: String,
    val userEmail: String,
    val baseCurrency: String,
)

/**
 * Desktop replacement for the DataStore-backed store.
 *
 * Same public surface, so [com.dividendstream.app.data.remote.NetworkModule] and
 * `AuthRepository` compile against it unchanged: a synchronous [current] for OkHttp's
 * interceptor and authenticator, which run on network threads and cannot suspend, and a
 * [sessions] flow for the UI.
 *
 * Tokens are written to a file in the user's local profile. That is protected by the account's
 * own permissions, not by encryption — the same caveat the Android build carries, and worth
 * hardening (DPAPI here, keystore there) before anyone ships this.
 */
class SessionStore(
    private val file: Path = AppPaths.state.resolve("session.json"),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(readFromDisk())

    /** Last known session, readable without suspending. Null when signed out. */
    val current: Session? get() = state.value

    val sessions: Flow<Session?> = state.asStateFlow()

    suspend fun load(): Session? = state.value

    suspend fun save(session: Session) {
        state.value = session
        withContext(Dispatchers.IO) { writeToDisk(session) }
    }

    suspend fun clear() {
        state.value = null
        withContext(Dispatchers.IO) { Files.deleteIfExists(file) }
    }

    private fun readFromDisk(): Session? = runCatching {
        if (!Files.exists(file)) return@runCatching null
        json.decodeFromString(Session.serializer(), Files.readString(file))
    }.getOrNull() // A file written by an older version may no longer parse; treat as signed out.

    private fun writeToDisk(session: Session) {
        // Written to a sibling and moved into place, so a crash mid-write cannot leave a
        // half-written file that reads back as "signed out" on the next launch.
        val temp = file.resolveSibling("session.json.tmp")
        Files.createDirectories(file.parent)
        Files.writeString(temp, json.encodeToString(Session.serializer(), session))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
