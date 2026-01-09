package com.bitnextechnologies.bitnexdial.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitnextechnologies.bitnexdial.domain.repository.IAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: IAuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val user = authRepository.login(email, password)
                _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
            } catch (e: Exception) {
                val errorMessage = parseErrorMessage(e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = errorMessage
                    )
                }
            }
        }
    }

    /**
     * Parse exception to provide user-friendly error messages
     */
    private fun parseErrorMessage(e: Exception): String {
        val message = e.message ?: ""
        return when {
            // Network errors
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("No address associated", ignoreCase = true) ->
                "No internet connection. Please check your network and try again."

            message.contains("timeout", ignoreCase = true) ||
            message.contains("timed out", ignoreCase = true) ->
                "Connection timed out. Please try again."

            message.contains("Connection refused", ignoreCase = true) ->
                "Unable to reach the server. Please try again later."

            // Authentication errors
            message.contains("401", ignoreCase = true) ||
            message.contains("Invalid credentials", ignoreCase = true) ||
            message.contains("invalid password", ignoreCase = true) ||
            message.contains("incorrect password", ignoreCase = true) ->
                "Invalid email or password. Please check your credentials."

            message.contains("404", ignoreCase = true) ||
            message.contains("User not found", ignoreCase = true) ||
            message.contains("account not found", ignoreCase = true) ->
                "Account not found. Please check your email address."

            message.contains("403", ignoreCase = true) ||
            message.contains("forbidden", ignoreCase = true) ||
            message.contains("disabled", ignoreCase = true) ||
            message.contains("suspended", ignoreCase = true) ->
                "Your account has been suspended. Please contact support."

            message.contains("429", ignoreCase = true) ||
            message.contains("too many", ignoreCase = true) ||
            message.contains("rate limit", ignoreCase = true) ->
                "Too many login attempts. Please wait a moment and try again."

            // Server errors
            message.contains("500", ignoreCase = true) ||
            message.contains("502", ignoreCase = true) ||
            message.contains("503", ignoreCase = true) ||
            message.contains("server error", ignoreCase = true) ->
                "Server error. Please try again later."

            // SSL/Certificate errors
            message.contains("SSL", ignoreCase = true) ||
            message.contains("certificate", ignoreCase = true) ->
                "Secure connection failed. Please check your network."

            // Generic fallback with original message if available
            message.isNotBlank() && message.length < 100 -> message

            else -> "Login failed. Please try again."
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
