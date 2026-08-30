package com.dividendstream.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.dataOrNull
import com.dividendstream.app.data.remote.GoogleConfigDto
import com.dividendstream.app.data.repository.SessionMirror
import com.dividendstream.app.data.remote.GoogleAuthAttempt
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
    /** What this server offers. Null until asked; Google is not shown before then. */
    val google: GoogleConfigDto? = null,
    /** A Google sign-in in progress. Separate, so it does not disable the password button. */
    val isGoogleSubmitting: Boolean = false,
    /**
     * Shown only when the server asked for a code and refused without one, which is the first
     * moment anyone can know it was needed: whether a Google sign-in creates an account is
     * something only the server can tell.
     */
    val needsInviteCode: Boolean = false,
    val inviteCode: String = "",
) {
    val canSubmit: Boolean
        get() = email.isNotBlank() && password.isNotBlank() && !isSubmitting && !isGoogleSubmitting

    val showGoogle: Boolean get() = google?.enabled == true
}

/**
 * Signs in to both halves of the app at once.
 *
 * The ledger lives in Firestore and the portfolio still lives behind the API, so there are two
 * accounts to hold and one password to do it with. Rather than ask twice, a successful sign-in
 * here is mirrored into Firebase, creating the Firebase account the first time if it is not
 * there yet. Nobody has to know there are two.
 *
 * [firebase] is null on the desktop, which has no Firebase SDK and stays wholly on the API.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val firebase: SessionMirror? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    init {
        loadGoogleConfig()
    }

    /**
     * Failure here is silent on purpose: it means Google sign-in is not offered this time, and
     * the password form beside it works perfectly well. An error about a sign-in method the
     * person may not even want would be noise in front of the one they can use.
     */
    private fun loadGoogleConfig() {
        viewModelScope.launch {
            val config = authRepository.googleConfig().dataOrNull() ?: return@launch
            _state.update { it.copy(google = config) }
        }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onInviteCodeChange(value: String) = _state.update { it.copy(inviteCode = value, error = null) }

    /**
     * Runs a Google sign-in end to end: the platform obtains something, the backend turns it
     * into a session.
     *
     * [launch] returning null means the person closed the picker, and nothing is said about it.
     */
    fun signInWithGoogle(launcher: GoogleSignInLauncher) {
        val config = _state.value.google ?: return
        if (_state.value.isGoogleSubmitting || _state.value.isSubmitting) return

        viewModelScope.launch {
            _state.update { it.copy(isGoogleSubmitting = true, error = null) }

            val attempt = try {
                launcher.launch(config)
            } catch (failed: GoogleSignInFailed) {
                _state.update {
                    it.copy(
                        isGoogleSubmitting = false,
                        error = AppError("GOOGLE_SIGN_IN_FAILED", failed.message.orEmpty(), isRetryable = true),
                    )
                }
                return@launch
            }

            if (attempt == null) {
                _state.update { it.copy(isGoogleSubmitting = false) }
                return@launch
            }

            when (val result = authRepository.signInWithGoogle(attempt, _state.value.inviteCode)) {
                is AppResult.Success -> {
                    // The same Google token both sides trust, so there is nothing to create.
                    // Only the phone's flow carries one: the desktop signs in with an
                    // authorisation code, which is redeemed by a server rather than by us.
                    (attempt as? GoogleAuthAttempt.IdToken)?.let { firebase?.signInWithGoogle(it.idToken) }
                    _state.update { it.copy(isGoogleSubmitting = false) }
                }
                is AppResult.Failure -> _state.update {
                    it.copy(
                        isGoogleSubmitting = false,
                        // The server has just told us this Google account is new here and the
                        // code is required. Revealing the field now, rather than always, keeps
                        // it out of the way of everyone signing back in.
                        needsInviteCode = it.needsInviteCode || result.error.code == "INVALID_INVITE_CODE",
                        error = result.error,
                    )
                }
            }
        }
    }

    /**
     * Signs in to Firebase with the same details, creating the account if this is the first time.
     *
     * Failures are swallowed on purpose. The person is signed in as far as they can tell, and
     * the ledger being empty until this succeeds is a better outcome than an error about a
     * second system they were never told existed. It is retried on every sign-in.
     */
    private suspend fun mirrorToFirebase(name: String, email: String, password: String) {
        val session = firebase ?: return
        if (session.signIn(email, password) == "NO_SUCH_USER") {
            session.createAccount(name, email, password)
        }
    }

    /** Navigation is driven by [com.dividendstream.app.ui.SessionViewModel] observing the store. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = authRepository.login(current.email, current.password)) {
                is AppResult.Success -> {
                    mirrorToFirebase(result.data.userName, current.email, current.password)
                    _state.update { it.copy(isSubmitting = false) }
                }

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

class RegisterViewModel(
    private val authRepository: AuthRepository,
    private val firebase: SessionMirror? = null,
) : ViewModel() {

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
                is AppResult.Success -> {
                    // Same password, same person, second system. See LoginViewModel.
                    firebase?.createAccount(current.name, current.email, current.password)
                    _state.update { it.copy(isSubmitting = false) }
                }

                is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, error = result.error) }
            }
        }
    }
}
