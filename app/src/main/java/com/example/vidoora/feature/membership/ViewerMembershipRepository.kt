package com.example.vidoora.feature.membership

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.security.SecureStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class MembershipResponse(val accountId: String? = null, val planId: String = "FREE",
    val adFree: Boolean = false, val maxHeight: Int? = 480, val expiresAt: String? = null,
    val validForSeconds: Long = 0, val downloadsPerPeriod: Int = 0, val purchasesAvailable: Boolean = false)

data class MembershipView(val planId: String = "FREE", val adFree: Boolean = false,
    val maxHeight: Int? = 480, val expiresAt: String? = null, val loading: Boolean = true)

/** Memory only. A monotonic lease cannot survive process death, reboot, account switch or clock rollback. */
internal class MembershipLease private constructor(private val identity: String, private val started: Long,
    private val duration: Long, val response: MembershipResponse) {
    fun valid(current: String?, now: Long) = current == identity && now >= started && now - started < duration
    fun remaining(now: Long) = (duration - (now - started)).coerceAtLeast(0)
    companion object {
        fun accept(identity: String, response: MembershipResponse, started: Long): MembershipLease? {
            if (started < 0 || response.validForSeconds !in 1..60) return null
            if (identity != (response.accountId?.let { "user:$it" } ?: "guest")) return null
            if (response.planId == "FREE") {
                if (response.adFree || response.maxHeight != 480 || response.downloadsPerPeriod != 0) return null
            } else if (response.planId == "ADMIN") {
                if (response.accountId == null || !response.adFree || response.maxHeight != null ||
                    response.downloadsPerPeriod != Int.MAX_VALUE ||
                    runCatching { Instant.parse(response.expiresAt) }.isFailure) return null
            } else {
                val downloads = mapOf("DAY" to 0, "WEEK" to 5, "MONTHLY" to 30)[response.planId] ?: return null
                if (response.accountId == null || !response.adFree || response.maxHeight != null || response.downloadsPerPeriod != downloads) return null
                if (runCatching { Instant.parse(response.expiresAt) }.isFailure) return null
            }
            return MembershipLease(identity, started, response.validForSeconds * 1000, response)
        }
    }
}

@Singleton
class ViewerMembershipRepository internal constructor(private val client: HttpClient,
    private val identity: () -> String?, private val elapsed: () -> Long) {
    @Inject constructor(client: HttpClient, storage: SecureStorageManager) : this(client, storage::watchIdentity, SystemClock::elapsedRealtime)
    private val mutex = Mutex()
    private var expected: String? = null
    private var lease: MembershipLease? = null
    private var fetching = false
    private var attempted = false
    private val mutable = MutableStateFlow(MembershipView())
    val state = mutable.asStateFlow()

    fun current(): MembershipView {
        val who = identity()
        if (who == null || who != expected) return MembershipView()
        val valid = lease?.takeIf { it.valid(who, elapsed()) }
        val value = valid?.response
        return if (value == null) MembershipView(loading = fetching || !attempted || lease?.response?.adFree == true)
            else MembershipView(value.planId, value.adFree, value.maxHeight, value.expiresAt, false)
    }
    fun tick() { mutable.value = current() }

    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val who = identity()
        if (who != expected) { expected = who; lease = null; attempted = false }
        if (who == null) { tick(); return@withLock }
        val old = lease
        if (!force && old != null && old.valid(who, elapsed()) && old.remaining(elapsed()) > 5_000) { tick(); return@withLock }
        fetching = true; tick()
        // Anchor before the request: network/verification delay must never extend a paid lease.
        val started = elapsed()
        try {
            val response = client.get("api/v1/membership") { timeout { requestTimeoutMillis = 15_000 } }
            val data = if (response.status == HttpStatusCode.OK) response.body<ApiEnvelope<MembershipResponse>>()
                .takeIf { it.success }?.data else null
            if (identity() == who) lease = data?.let { MembershipLease.accept(who, it, started) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (identity() == who) lease = null }
        finally { fetching = false; if (identity() == who) attempted = true; tick() }
    }
}

@HiltViewModel
class MembershipViewModel @Inject constructor(val membership: ViewerMembershipRepository) : ViewModel()
