package com.example.vidoora.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.network.dto.LoginRequestDto
import com.example.vidoora.core.network.dto.RegisterRequestDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val username: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, pass: String) {
        val emailTrimmed = email.trim()
        val passTrimmed = pass

        if (emailTrimmed.isBlank() || !emailTrimmed.contains("@")) {
            _uiState.value = AuthUiState.Error("Please enter a valid email address.")
            return
        }
        if (passTrimmed.length < 8) {
            _uiState.value = AuthUiState.Error("Password must be at least 8 characters long.")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val request = LoginRequestDto(email = emailTrimmed, password = passTrimmed)
            when (val result = authRepository.login(request)) {
                is Resource.Success -> {
                    _uiState.value = AuthUiState.Success(result.data.user.displayName)
                }
                is Resource.Error -> {
                    _uiState.value = AuthUiState.Error(result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun register(username: String, email: String, pass: String, displayName: String) {
        val userTrimmed = username.trim()
        val emailTrimmed = email.trim()
        val passTrimmed = pass
        val nameTrimmed = displayName.trim()

        if (emailTrimmed.isBlank() || !emailTrimmed.contains("@")) {
            _uiState.value = AuthUiState.Error("Please enter a valid email address.")
            return
        }
        if (passTrimmed.length < 8) {
            _uiState.value = AuthUiState.Error("Password must be at least 8 characters long.")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val request = RegisterRequestDto(
                username = "",
                email = emailTrimmed,
                password = passTrimmed,
                displayName = "",
                creatorInviteCode = userTrimmed
            )
            when (val result = authRepository.register(request)) {
                is Resource.Success -> {
                    _uiState.value = AuthUiState.Success(result.data.user.displayName)
                }
                is Resource.Error -> {
                    _uiState.value = AuthUiState.Error(result.message)
                }
                is Resource.Loading -> {}
            }
        }
    }

    fun clearState() {
        _uiState.value = AuthUiState.Idle
    }
}
