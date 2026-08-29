package com.dividendstream.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ThemePreference
import com.dividendstream.app.data.local.SettingsStore
import com.dividendstream.app.data.remote.AppVersionDto
import com.dividendstream.app.data.remote.UserProfileDto
import com.dividendstream.app.data.repository.AppInfoRepository
import com.dividendstream.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfileDto? = null,
    /** The name as it is being typed. Separate from [profile], which is what the server has. */
    val name: String = "",
    val baseCurrency: String = "MYR",
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val error: AppError? = null,
    /** What the backend reports about itself. Absent when it could not be reached. */
    val backend: AppVersionDto? = null,
) {
    /** Nothing to save until something actually differs from what the server holds. */
    val hasChanges: Boolean
        get() = profile != null &&
            (name.trim() != profile.name || baseCurrency != profile.baseCurrency)

    val canSave: Boolean get() = hasChanges && name.isNotBlank() && !isSaving
}

class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val appInfoRepository: AppInfoRepository,
    private val settingsStore: SettingsStore,
    /** This build's own release number, so the screen can say which one is running. */
    val appVersion: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state = _state.asStateFlow()

    val theme = settingsStore.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.System)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.profile == null, error = null) }

            when (val result = authRepository.profile()) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        profile = result.data,
                        // Only overwrite the fields while they are untouched, so a slow response
                        // cannot wipe out what the person has already started typing.
                        name = if (it.hasChanges) it.name else result.data.name,
                        baseCurrency = if (it.hasChanges) it.baseCurrency else result.data.baseCurrency,
                        error = null,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }

        viewModelScope.launch {
            // Never surfaced as an error: which build is running is a diagnostic, and failing to
            // fetch it is not something to interrupt somebody with.
            val info = runCatching { appInfoRepository.version() }.getOrNull() ?: return@launch
            _state.update { it.copy(backend = info) }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, savedMessage = null) }

    fun onCurrencyChange(value: String) =
        _state.update { it.copy(baseCurrency = value, savedMessage = null) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null, savedMessage = null) }

            when (val result = authRepository.updateProfile(current.name, current.baseCurrency)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isSaving = false,
                        profile = result.data,
                        name = result.data.name,
                        baseCurrency = result.data.baseCurrency,
                        savedMessage = "Saved",
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isSaving = false, error = result.error)
                }
            }
        }
    }

    fun setTheme(preference: ThemePreference) {
        viewModelScope.launch { settingsStore.setTheme(preference) }
    }
}
