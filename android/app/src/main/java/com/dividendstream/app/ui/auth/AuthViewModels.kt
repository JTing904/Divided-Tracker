package com.dividendstream.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting
}

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Navigation is driven by [com.dividendstream.app.ui.SessionViewModel] observing the store. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = authRepository.login(current.email, current.password)) {
                is AppResult.Success -> _state.update { it.copy(isSubmitting = false) }
                is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, error = result.error) }
            }
        }
    }
}

data class RegisterUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    /** Blank unless the server asks for one; the server decides, not the app. */
    val inviteCode: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
) {
    /**
     * Checked here rather than in the composable, and mirrored by the backend's own
     * validation -- the client check is a courtesy, not the guarantee.
     */
    val passwordMismatch: Boolean
        get() = confirmPassword.isNotEmpty() && password != confirmPassword

    val passwordTooShort: Boolean
        get() = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH

    val canSubmit: Boolean
        get() = name.isNotBlank() && email.isNotBlank() &&
            password.length >= MIN_PASSWORD_LENGTH && password == confirmPassword && !isSubmitting

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

class RegisterViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state = _state.asStateFlow()

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }

    fun onInviteCodeChange(value: String) = _state.update { it.copy(inviteCode = value, error = null) }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            val result = authRepository.register(
                current.name, current.email, current.password, current.inviteCode,
            )
            when (result) {
                is AppResult.Success -> _state.update { it.copy(isSubmitting = false) }
                is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, error = result.error) }
            }
        }
    }
}
