package com.dividendstream.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dividendstream.app.core.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "dividend_stream_settings")

/**
 * Preferences that belong to this device rather than to the account.
 *
 * Kept apart from [SessionStore] deliberately: signing out clears a session, and it must not
 * take the person's choice of theme with it. Kept off the server for the same reason -- which
 * scheme suits a phone in daylight is not a fact about an account.
 */
class SettingsStore(private val context: Context) {

    val theme: Flow<ThemePreference> = context.settingsDataStore.data.map { prefs ->
        ThemePreference.of(prefs[KEY_THEME])
    }

    suspend fun setTheme(preference: ThemePreference) {
        context.settingsDataStore.edit { it[KEY_THEME] = preference.key }
    }

    /**
     * Which stretch of time the ledger was last showing, as its wire value.
     *
     * A string rather than the enum: the enum belongs to the ledger screen, and a storage
     * class that has to import a screen's types to save a preference has the dependency the
     * wrong way round. Null when nothing has been chosen yet.
     */
    val ledgerPeriod: Flow<String?> = context.settingsDataStore.data.map { it[KEY_LEDGER_PERIOD] }

    suspend fun setLedgerPeriod(wire: String) {
        context.settingsDataStore.edit { it[KEY_LEDGER_PERIOD] = wire }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_LEDGER_PERIOD = stringPreferencesKey("ledger_period")
    }
}
