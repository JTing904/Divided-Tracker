package com.dividendstream.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.data.local.Session
import com.dividendstream.app.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    /** Reading the stored session. The splash screen is shown for this state. */
    data object Loading : SessionState

    data object SignedOut : SessionState

    data class SignedIn(val session: Session) : SessionState
}

/**
 * Owns "is anyone signed in", for the whole app.
 *
 * It observes the session store rather than being told, so a token refresh failing deep
 * inside the HTTP stack clears the session and the UI returns to the login screen on its
 * own -- no screen needs to handle that case.
 */
class SessionViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.sessions.collect { session ->
                _state.value = if (session == null) SessionState.SignedOut else SessionState.SignedIn(session)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.logout() }
    }
}
