package com.dividendstream.app.data.local

import com.dividendstream.app.AppPaths
import com.dividendstream.app.core.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Desktop replacement for the DataStore-backed store, with the same public surface.
 *
 * Read once at construction so the very first frame is already painted in the chosen scheme:
 * a window that opens dark and turns light a moment later looks like a fault, and the file is
 * a single short line.
 */
class SettingsStore(private val directory: Path = AppPaths.state) {

    private val state = MutableStateFlow(readFromDisk())

    val theme: Flow<ThemePreference> = state.asStateFlow()

    suspend fun setTheme(preference: ThemePreference) {
        state.value = preference
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val target = directory.resolve(FILE)
            val temp = directory.resolve("$FILE.tmp")
            Files.writeString(temp, preference.key)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** See the Android store: a string, so this class need not know the ledger's types. */
    private val period = MutableStateFlow(
        runCatching { Files.readString(directory.resolve(PERIOD_FILE)).trim().ifBlank { null } }
            .getOrNull(),
    )

    val ledgerPeriod: Flow<String?> = period.asStateFlow()

    suspend fun setLedgerPeriod(wire: String) {
        period.value = wire
        withContext(Dispatchers.IO) { writeLine(PERIOD_FILE, wire) }
    }

    private fun readFromDisk(): ThemePreference =
        runCatching { ThemePreference.of(Files.readString(directory.resolve(FILE)).trim()) }
            .getOrDefault(ThemePreference.System)

    /** Written through a temporary file, so a crash mid-write cannot leave half a value. */
    private fun writeLine(name: String, value: String) {
        Files.createDirectories(directory)
        val target = directory.resolve(name)
        val temp = directory.resolve("$name.tmp")
        Files.writeString(temp, value)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val FILE = "theme"
        const val PERIOD_FILE = "ledger-period"
    }
}
