package com.example.vidoora.core.security

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.network.dto.RefreshTokenRequestDto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class LogoutAcknowledgement(val accepted: Boolean=false)

@Singleton
class SessionRevocations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: SecureStorageManager,
    private val client: HttpClient
) {
    fun enqueue() { if(storage.pendingRevocations().isNotEmpty()) SessionRevocationWorker.enqueue(context) }
    suspend fun flush(): Boolean {
        for(token in storage.pendingRevocations().take(20)) {
            try {
                val response=client.post("api/v1/auth/logout") {
                    setBody(RefreshTokenRequestDto(token))
                    timeout { requestTimeoutMillis=5_000; connectTimeoutMillis=5_000; socketTimeoutMillis=5_000 }
                }
                if(response.status.value!=200) return false
                val ack=response.body<ApiEnvelope<LogoutAcknowledgement>>()
                if(!ack.success || ack.data?.accepted!=true) return false
                storage.acknowledgeRevocation(token)
            } catch(e: CancellationException) { throw e }
            catch (_: Exception) { return false }
        }
        return storage.pendingRevocations().isEmpty()
    }
}

@HiltWorker
class SessionRevocationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val revocations: SessionRevocations
): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = if(revocations.flush()) Result.success() else Result.retry()
    companion object {
        fun enqueue(context: Context) {
            val work=OneTimeWorkRequestBuilder<SessionRevocationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            // Append so a logout arriving as an earlier worker finishes is never lost behind KEEP.
            WorkManager.getInstance(context).enqueueUniqueWork("session_revocations",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
    }
}
