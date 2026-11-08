package com.example.vidoora.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.network.dto.UserDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val currentUser: StateFlow<UserDto?> = authRepository.getCachedUser()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun logout(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }

    suspend fun refreshProfile() = authRepository.refreshProfile()
}
