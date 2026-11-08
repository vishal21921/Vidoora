package com.example.vidoora.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.network.dto.UserDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val userProfile: StateFlow<UserDto?> = authRepository.getCachedUser()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val busy=MutableStateFlow(false)
    val loggingOut=busy.asStateFlow()
    private val errors=MutableStateFlow<String?>(null)
    val logoutError=errors.asStateFlow()
    fun logout(onComplete: (String?)->Unit = {}) {
        if(busy.value) return
        busy.value=true; errors.value=null
        viewModelScope.launch {
            try {
                authRepository.logout()
                val confirmed=authRepository.finishLogout()
                onComplete(if(confirmed) null else "Signed out on this phone. Server sign-out will finish when connected.")
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { errors.value="Unable to finish sign-out. Please retry." }
            finally { busy.value=false }
        }
    }
}
