package com.example.vidoora.feature.membership

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.core.security.SecureStorageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class PaymentItem(val reference: String,val paymentState: String,val activationState: String,
    val planId: String?=null,val expiresAt: String?=null,val createdAt: String,val updatedAt: String,val retryable: Boolean=false)
@Serializable data class PaymentsPage(val available: Boolean=false,val items: List<PaymentItem> = emptyList(),val nextCursor: String?=null)
@Serializable internal data class RecoveryConfig(val restoreAvailable: Boolean=false,val purchasesAvailable: Boolean=false,val products: List<ProductMapping> = emptyList())
@Serializable internal data class ProductMapping(val planId: String,val productId: String,val basePlanId: String)
@Serializable internal class ReceiptBody(val token: String) { override fun toString()="ReceiptBody(redacted)" }
@Serializable internal class PendingReceipt(val account: String,val token: String) { override fun toString()="PendingReceipt(redacted)" }

/** Encrypted preferences only: WorkManager Data contains no token, account key or private receipt. */
@Singleton
class ReceiptOutbox internal constructor(private val storage: SecureStorageManager,private val client: HttpClient,
    private val schedule: ()->Unit,private val ensureSweep: ()->Unit) {
    @Inject constructor(storage: SecureStorageManager,client: HttpClient,@ApplicationContext context: Context):
        this(storage,client,{ReceiptRecoveryWorker.enqueue(context)},{ReceiptRecoveryWorker.ensureSweep(context)})
    private val mutex=Mutex()
    private fun read(): Map<String,PendingReceipt> {
        val raw=storage.readReceiptOutbox() ?: return emptyMap()
        check(raw.length<=262144)
        return Json.decodeFromString<Map<String,PendingReceipt>>(raw).also {check(it.size<=32)}
    }
    private fun key(account: String,token: String)=MessageDigest.getInstance("SHA-256")
        .digest("$account|$token".toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    suspend fun accept(account: String,token: String)=withContext(Dispatchers.IO) {
        require(UUID.fromString(account).toString()==account && token.isNotBlank() && token.length<=4096)
        ensureSweep()
        mutex.withLock {
            check(storage.watchIdentity()=="user:$account") {"Account changed"}
            val entries=read().toMutableMap();val id=key(account,token)
            check(id in entries || entries.size<32) {"Too many receipts awaiting verification"}
            entries[id]=PendingReceipt(account,token)
            check(storage.writeReceiptOutbox(Json.encodeToString(entries))) {"Could not save receipt"}
        }
        schedule()
    }
    suspend fun flush(): Boolean=withContext(Dispatchers.IO) { mutex.withLock {
        val identity=storage.watchIdentity() ?: return@withLock true
        val account=identity.takeIf{it.startsWith("user:")}?.removePrefix("user:") ?: return@withLock true
        val entries=read().toMutableMap()
        for((id,receipt) in entries.toMap()) {
            if(receipt.account!=account) continue
            if(storage.watchIdentity()!=identity) return@withLock true
            val response=client.post("api/v1/payments/receipts") { setBody(ReceiptBody(receipt.token)) }
            if(storage.watchIdentity()!=identity) return@withLock true
            if(response.status !in listOf(HttpStatusCode.Accepted,HttpStatusCode.OK)) return@withLock false
            val accepted=response.body<ApiEnvelope<PaymentItem>>()
            if(!accepted.success || runCatching{UUID.fromString(checkNotNull(accepted.data?.reference))}.isFailure) return@withLock false
            entries.remove(id)
            check(storage.writeReceiptOutbox(Json.encodeToString(entries)))
        }
        true
    } }
}

@HiltWorker
class ReceiptRecoveryWorker @AssistedInject constructor(@Assisted context: Context,@Assisted parameters: WorkerParameters,
    private val outbox: ReceiptOutbox) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result=try {if(outbox.flush()) Result.success() else Result.retry()}
        catch(e: CancellationException){throw e} catch (_: Exception){Result.retry()}
    companion object {
        fun ensureSweep(context: Context) {
            val request=PeriodicWorkRequestBuilder<ReceiptRecoveryWorker>(15,java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("vidoora-play-receipt-sweep",ExistingPeriodicWorkPolicy.KEEP,request)
        }
        fun enqueue(context: Context) {
            val request=OneTimeWorkRequestBuilder<ReceiptRecoveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("vidoora-play-receipts",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}

data class PaymentsState(val loading: Boolean=true,val available: Boolean=false,val items: List<PaymentItem> = emptyList(),
    val nextCursor: String?=null,val error: String?=null)

@HiltViewModel
class PaymentsViewModel @Inject constructor(private val client: HttpClient,private val storage: SecureStorageManager,
    val outbox: ReceiptOutbox,private val membership: ViewerMembershipRepository): ViewModel() {
    private val mutable=MutableStateFlow(PaymentsState());val state=mutable.asStateFlow()
    private var busy=false
    private var owner: String?=null
    internal var config by androidx.compose.runtime.mutableStateOf(RecoveryConfig());private set
    suspend fun refresh(more: Boolean=false) {
        val identity=storage.watchIdentity()
        if(identity==null || !identity.startsWith("user:")) {mutable.value=PaymentsState(loading=false);config=RecoveryConfig();return}
        if(owner!=identity) {owner=identity;config=RecoveryConfig();mutable.value=PaymentsState()}
        if(busy) return
        busy=true
        val cursor=if(more) mutable.value.nextCursor else null
        mutable.value=mutable.value.copy(loading=true,error=null)
        try {
            val response=client.get("api/v1/payments") {cursor?.let {parameter("cursor",it)}}
            check(response.status==HttpStatusCode.OK)
            val page=response.body<ApiEnvelope<PaymentsPage>>().takeIf{it.success}?.data ?: error("Unavailable")
            val setup=client.get("api/v1/payments/config").body<ApiEnvelope<RecoveryConfig>>().takeIf{it.success}?.data ?: RecoveryConfig()
            if(storage.watchIdentity()!=identity) return
            config=setup
            val items=if(more) (mutable.value.items+page.items).distinctBy{it.reference} else page.items
            mutable.value=PaymentsState(false,page.available,items,page.nextCursor)
            if(items.any{it.activationState=="ACTIVE"}) membership.refresh(force=true)
        } catch(e: CancellationException){throw e}
        catch (_: Exception){if(storage.watchIdentity()==identity) mutable.value=mutable.value.copy(loading=false,error="Could not load payments. Try again.")}
        finally {busy=false}
    }
    fun account(): String?=storage.watchIdentity()?.takeIf{it.startsWith("user:")}?.removePrefix("user:")
    fun owned(value: PaymentsState)=if(owner==storage.watchIdentity()) value else PaymentsState()
    fun retry(reference: String) {viewModelScope.launch {
        val identity=storage.watchIdentity()
        try {val response=client.post("api/v1/payments/${UUID.fromString(reference)}/retry");check(response.status.value in 200..299);if(storage.watchIdentity()==identity) refresh()}
        catch(e: CancellationException){throw e} catch (_: Exception){if(storage.watchIdentity()==identity) mutable.value=mutable.value.copy(error="Could not retry verification. Try again.")}
    }}
}
