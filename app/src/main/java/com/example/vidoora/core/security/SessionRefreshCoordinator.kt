package com.example.vidoora.core.security

import io.ktor.client.plugins.auth.providers.BearerTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

sealed interface RefreshOutcome {
    class Success(val access: String,val refresh: String): RefreshOutcome {
        override fun toString()="RefreshOutcome.Success(<redacted>)"
    }
    data object Rejected: RefreshOutcome
    data object Unavailable: RefreshOutcome
}

/** Shared by every HTTP client and repository logout; never rotate concurrently on one device. */
class SessionRefreshCoordinator(private val storage: SecureStorageManager,private val onEnded: ()->Unit={}) {
    suspend fun refresh(failedAccess: String?,request: suspend (String)->RefreshOutcome): BearerTokens? = storage.sessionMutex.withLock {
        val current=storage.tokenPair() ?: return@withLock null
        // Another client has already renewed the failed access token.
        if(failedAccess!=null && current.first!=failedAccess) return@withLock BearerTokens(current.first,current.second)
        val result=try { request(current.second) } catch(e: CancellationException) { throw e } catch (_: Exception) { RefreshOutcome.Unavailable }
        when(result) {
            is RefreshOutcome.Success -> {
                if(storage.replaceTokens(current.second,result.access,result.refresh)) BearerTokens(result.access,result.refresh) else null
            }
            RefreshOutcome.Rejected -> { storage.endSession(); onEnded(); null }
            RefreshOutcome.Unavailable -> null // Keep local credentials after temporary network/server failures.
        }
    }
}
