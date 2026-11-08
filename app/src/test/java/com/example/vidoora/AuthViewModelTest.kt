package com.example.vidoora

import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.AuthRepository
import com.example.vidoora.core.network.dto.LoginRequestDto
import com.example.vidoora.core.network.dto.RegisterRequestDto
import com.example.vidoora.core.network.dto.TokenResponseDto
import com.example.vidoora.core.network.dto.UserDto
import com.example.vidoora.feature.auth.AuthUiState
import com.example.vidoora.feature.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeRepository = FakeAuthRepository()
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AuthViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun login_invalidEmail_setsErrorState() = runTest {
        viewModel.login("invalid-email", "Password123!")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthUiState.Error)
        assertEquals("Please enter a valid email address.", (state as AuthUiState.Error).message)
    }

    @Test
    fun login_shortPassword_setsErrorState() = runTest {
        viewModel.login("test@vidoora.app", "123")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthUiState.Error)
        assertEquals("Password must be at least 8 characters long.", (state as AuthUiState.Error).message)
    }

    @Test
    fun login_validCredentials_setsSuccessState() = runTest {
        viewModel.login("test@vidoora.app", "Password123!")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthUiState.Success)
        assertEquals("Test User", (state as AuthUiState.Success).username)
    }
}

class FakeAuthRepository : AuthRepository {
    override suspend fun register(request: RegisterRequestDto): Resource<TokenResponseDto> {
        val user = UserDto(
            id = "uuid-1",
            username = request.username,
            email = request.email,
            displayName = request.displayName,
            createdAt = "2026-10-12T10:00:00Z"
        )
        return Resource.Success(TokenResponseDto("access", "refresh", user))
    }

    override suspend fun login(request: LoginRequestDto): Resource<TokenResponseDto> {
        val user = UserDto(
            id = "uuid-1",
            username = "testuser",
            email = request.email,
            displayName = "Test User",
            createdAt = "2026-10-12T10:00:00Z"
        )
        return Resource.Success(TokenResponseDto("access", "refresh", user))
    }

    override suspend fun restoreSession(): Resource<UserDto> {
        val user = UserDto(
            id = "uuid-1",
            username = "testuser",
            email = "test@vidoora.app",
            displayName = "Test User",
            createdAt = "2026-10-12T10:00:00Z"
        )
        return Resource.Success(user)
    }

    override suspend fun logout() {}

    override fun getCachedUser(): Flow<UserDto?> {
        return flowOf(null)
    }
}
