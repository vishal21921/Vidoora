package com.example.vidoora.core.data.repository

import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.database.dao.UserDao
import com.example.vidoora.core.database.entity.UserEntity
import com.example.vidoora.core.network.dto.*
import com.example.vidoora.core.security.SecureStorageManager
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.withLock
import com.example.vidoora.core.security.SessionRevocations
import javax.inject.Inject
import javax.inject.Singleton

interface AuthRepository {
    suspend fun register(request: RegisterRequestDto): Resource<TokenResponseDto>
    suspend fun redeemCreator(code: String): Resource<UserDto> = Resource.Error("Creator access unavailable")
    suspend fun login(request: LoginRequestDto): Resource<TokenResponseDto>
    suspend fun restoreSession(): Resource<UserDto>
    suspend fun refreshProfile() {}
    suspend fun logout()
    suspend fun finishLogout(): Boolean = true
    fun getCachedUser(): Flow<UserDto?>
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val httpClient: HttpClient,
    private val secureStorageManager: SecureStorageManager,
    private val userDao: UserDao,
    private val uploads: UploadRepositoryImpl,
    private val videoShareAccess: com.example.vidoora.core.network.VideoShareAccess,
    private val revocations: SessionRevocations
) : AuthRepository {
    override suspend fun redeemCreator(code: String): Resource<UserDto> = withContext(Dispatchers.IO) { accountOperations.withLock {
        try {
            val response=httpClient.post("api/v1/users/me/creator-invite") {setBody(mapOf("code" to code.trim()))}
            val result=response.body<ApiEnvelope<UserDto>>()
            val user=result.data
            if(response.status==HttpStatusCode.OK && user!=null && result.success) {
                userDao.insertUser(user.toEntity()); Resource.Success(user)
            } else Resource.Error(result.error?.message ?: "Unable to redeem invitation")
        } catch(e: CancellationException) {throw e} catch(_: Exception) {Resource.Error("Unable to redeem invitation. Retry online.")}
    } }
    // Serialize account/Room changes separately from the token-refresh mutex.
    private val accountOperations=kotlinx.coroutines.sync.Mutex()

    override suspend fun register(request: RegisterRequestDto): Resource<TokenResponseDto> = withContext(Dispatchers.IO) { accountOperations.withLock { registerInternal(request) } }

    private suspend fun registerInternal(request: RegisterRequestDto): Resource<TokenResponseDto> {
        return try {
            val response = httpClient.post("api/v1/auth/register") {
                setBody(request)
            }
            if (response.status == HttpStatusCode.Created) {
                val envelope = response.body<ApiEnvelope<TokenResponseDto>>()
                val data = envelope.data
                if (envelope.success && data != null) {
                    userDao.getUserOnce()?.takeIf { it.id != data.user.id }?.let { uploads.stopForLogout(it.id) }
                    videoShareAccess.clear()
                    secureStorageManager.sessionMutex.withLock {
                        secureStorageManager.endSession()
                        secureStorageManager.saveTokens(data.accessToken,data.refreshToken)
                        clearBearerCache()
                    }
                    revocations.enqueue()
                    userDao.clearUser()
                    userDao.insertUser(data.user.toEntity())
                    uploads.recoverForUser(data.user.id)
                    Resource.Success<TokenResponseDto>(data)
                } else {
                    Resource.Error(envelope.error?.message ?: "Registration failed", envelope.error?.code)
                }
            } else {
                val envelope = response.body<ApiEnvelope<Unit>>()
                Resource.Error(envelope.error?.message ?: "Registration failed with status ${response.status}", envelope.error?.code)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Resource.Error("Unable to complete sign-in. Check your connection and retry.")
        }
    }

    override suspend fun login(request: LoginRequestDto): Resource<TokenResponseDto> = withContext(Dispatchers.IO) { accountOperations.withLock { loginInternal(request) } }

    private suspend fun loginInternal(request: LoginRequestDto): Resource<TokenResponseDto> {
        return try {
            val response = httpClient.post("api/v1/auth/login") {
                setBody(request)
            }
            if (response.status == HttpStatusCode.OK) {
                val envelope = response.body<ApiEnvelope<TokenResponseDto>>()
                val data = envelope.data
                if (envelope.success && data != null) {
                    userDao.getUserOnce()?.takeIf { it.id != data.user.id }?.let { uploads.stopForLogout(it.id) }
                    videoShareAccess.clear()
                    secureStorageManager.sessionMutex.withLock {
                        secureStorageManager.endSession()
                        secureStorageManager.saveTokens(data.accessToken,data.refreshToken)
                        clearBearerCache()
                    }
                    revocations.enqueue()
                    userDao.clearUser()
                    userDao.insertUser(data.user.toEntity())
                    uploads.recoverForUser(data.user.id)
                    Resource.Success<TokenResponseDto>(data)
                } else {
                    Resource.Error(envelope.error?.message ?: "Login failed", envelope.error?.code)
                }
            } else {
                val envelope = response.body<ApiEnvelope<Unit>>()
                Resource.Error(envelope.error?.message ?: "Login failed with status ${response.status}", envelope.error?.code)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Resource.Error("Unable to complete sign-in. Check your connection and retry.")
        }
    }

    override suspend fun restoreSession(): Resource<UserDto> = withContext(Dispatchers.IO) { accountOperations.withLock { restoreSessionInternal() } }

    override suspend fun refreshProfile() = withContext(Dispatchers.IO) { accountOperations.withLock {
        val cached = userDao.getUserOnce() ?: return@withLock
        if (secureStorageManager.getRefreshToken() == null) return@withLock
        try {
            val response = httpClient.get("api/v1/users/me")
            if (response.status == HttpStatusCode.OK) {
                val user = response.body<ApiEnvelope<UserDto>>().data ?: return@withLock
                if (user.id == cached.id && user.toEntity() != cached) userDao.insertUser(user.toEntity())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the cached profile offline; server routes still enforce the current role.
        }
    } }

    private suspend fun restoreSessionInternal(): Resource<UserDto> {
        revocations.enqueue()
        val refreshToken = secureStorageManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            logoutInternal()
            return Resource.Error("No saved session token found")
        }

        return try {
            val response = httpClient.get("api/v1/users/me")
            if (response.status == HttpStatusCode.OK) {
                val envelope = response.body<ApiEnvelope<UserDto>>()
                val user = envelope.data
                if (envelope.success && user != null) {
                    userDao.getUserOnce()?.takeIf { it.id != user.id }?.let { uploads.stopForLogout(it.id) }
                    userDao.clearUser()
                    userDao.insertUser(user.toEntity())
                    uploads.recoverForUser(user.id)
                    Resource.Success<UserDto>(user)
                } else {
                    logoutInternal()
                    Resource.Error(envelope.error?.message ?: "Session invalid")
                }
            } else {
                if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) logoutInternal()
                Resource.Error("Unable to restore session (${response.status.value})")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Resource.Error("Unable to restore the session. Check your connection and retry.")
        }
    }

    override suspend fun logout(): Unit = withContext(Dispatchers.IO) { accountOperations.withLock { logoutInternal() } }

    override suspend fun finishLogout(): Boolean = withContext(Dispatchers.IO) { revocations.flush() }

    private suspend fun logoutInternal() {
        userDao.getUserOnce()?.let { uploads.stopForLogout(it.id) }
        videoShareAccess.clear()
        secureStorageManager.sessionMutex.withLock { secureStorageManager.endSession(); clearBearerCache() }
        revocations.enqueue()
        userDao.clearUser()
    }

    override fun getCachedUser(): Flow<UserDto?> {
        return userDao.getUser().combine(secureStorageManager.sessionChanges) { user, _ ->
            user?.takeIf { secureStorageManager.getRefreshToken()!=null }?.toDto()
        }
    }

    private fun clearBearerCache() {
        httpClient.plugin(Auth).providers.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    private fun UserDto.toEntity(): UserEntity = UserEntity(
        id = id,
        username = username,
        publicId = publicId,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        isSuspended = isSuspended,
        role = role,
        createdAt = createdAt
    )

    private fun UserEntity.toDto(): UserDto = UserDto(
        publicId = publicId,
        id = id,
        username = username,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = bio,
        isSuspended = isSuspended,
        role = role,
        createdAt = createdAt
    )
}







