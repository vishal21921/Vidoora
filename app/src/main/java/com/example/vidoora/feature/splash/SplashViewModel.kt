package com.example.vidoora.feature.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SplashState {
    object Initial : SplashState()
    object Authenticated : SplashState()
    object Unauthenticated : SplashState()
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _splashState = MutableStateFlow<SplashState>(SplashState.Initial)
    val splashState: StateFlow<SplashState> = _splashState.asStateFlow()

    init {
        restoreSession()
    }

    fun restoreSession() {
        viewModelScope.launch {
            when (authRepository.restoreSession()) {
                is Resource.Success -> {
                    _splashState.value = SplashState.Authenticated
                }
                else -> {
                    _splashState.value = SplashState.Unauthenticated
                }
            }
        }
    }
}
