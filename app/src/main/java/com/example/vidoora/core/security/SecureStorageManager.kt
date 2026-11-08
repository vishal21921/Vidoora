package com.example.vidoora.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorageManager internal constructor(private val context: Context,private val suppliedPreferences: SharedPreferences?) {
    @Inject constructor(@ApplicationContext context: Context):this(context,null)
    val sessionMutex=kotlinx.coroutines.sync.Mutex()
    private val changes=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val sessionChanges: kotlinx.coroutines.flow.StateFlow<Long> = changes
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPreferences: SharedPreferences by lazy {
        suppliedPreferences ?: EncryptedSharedPreferences.create(
                context,
                "vidoora_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun saveTokens(accessToken: String, refreshToken: String) {
        sharedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commit().also { check(it) { "Unable to save secure session" }; changes.value++ }
    }

    fun getAccessToken(): String? = sharedPreferences.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = sharedPreferences.getString(KEY_REFRESH_TOKEN, null)

    @Synchronized
    fun replaceTokens(expectedRefresh: String, accessToken: String, refreshToken: String): Boolean {
        if (getRefreshToken() != expectedRefresh) return false
        saveTokens(accessToken, refreshToken)
        return true
    }

    @Synchronized
    fun clearTokens() {
        sharedPreferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .commit().also { check(it) { "Unable to save secure session" }; changes.value++ }
    }

    @Synchronized
    fun tokenPair(): Pair<String,String>? {
        val access=getAccessToken() ?: return null
        val refresh=getRefreshToken() ?: return null
        return access to refresh
    }

    /** Commit logout and encrypted retry material together before stopping the local session. */
    @Synchronized
    fun endSession() {
        val pending=pendingRevocations().toMutableSet()
        getRefreshToken()?.let { pending+=it }
        check(sharedPreferences.edit().putStringSet(KEY_PENDING_LOGOUT,pending)
            .remove(KEY_ACCESS_TOKEN).remove(KEY_REFRESH_TOKEN).commit()) { "Unable to save secure logout" }
        changes.value++
    }
    @Synchronized fun pendingRevocations(): Set<String> = sharedPreferences.getStringSet(KEY_PENDING_LOGOUT,emptySet())!!.toSet()
    @Synchronized fun rememberRevocation(token: String) {
        check(sharedPreferences.edit().putStringSet(KEY_PENDING_LOGOUT,pendingRevocations()+token).commit()) { "Unable to save secure logout" }
    }
    @Synchronized fun acknowledgeRevocation(token: String) {
        check(sharedPreferences.edit().putStringSet(KEY_PENDING_LOGOUT,pendingRevocations()-token).commit()) { "Unable to save secure logout" }
    }
    /** Local watch namespace only; this does not validate a JWT or authorize video access. */
    @Synchronized fun watchIdentity(): String? {
        val pair=try { tokenPair() } catch (_: Exception) { return null }
        if(pair==null) return try { if(getAccessToken()==null && getRefreshToken()==null) "guest" else null } catch (_: Exception) { null }
        return try {
            if(pair.first.length>4096) return null
            val parts=pair.first.split('.')
            if(parts.size!=3) return null
            val payload=String(android.util.Base64.decode(parts[1],android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),Charsets.UTF_8)
            val subject=org.json.JSONObject(payload).getString("sub")
            "user:"+java.util.UUID.fromString(subject).toString()
        } catch (_: Exception) { null }
    }
    @Synchronized internal fun readWatchProgress(identity: String): String? =
        if(watchIdentity()==identity) sharedPreferences.getString(KEY_WATCH_PROGRESS,null) else null
    @Synchronized internal fun writeWatchProgress(identity: String,value: String): Boolean {
        if(watchIdentity()!=identity) return false
        return sharedPreferences.edit().putString(KEY_WATCH_PROGRESS,value).commit()
    }
    @Synchronized internal fun readWatchHistory(identity: String): String? =
        if (watchIdentity() == identity) sharedPreferences.getString("watch_history_v1:$identity", null) else null
    @Synchronized internal fun analyticsViewerKey(): String {
        sharedPreferences.getString("analytics_viewer_v1", null)?.let { return it }
        val key = java.util.UUID.randomUUID().toString()
        check(sharedPreferences.edit().putString("analytics_viewer_v1", key).commit())
        return key
    }

    @Synchronized internal fun writeWatchHistory(identity: String, value: String) {
        if (watchIdentity() != identity) return
        check(sharedPreferences.edit().putString("watch_history_v1:$identity", value).commit())
    }
    @Synchronized internal fun readReceiptOutbox(): String? = sharedPreferences.getString("play_receipt_outbox_v1",null)
    @Synchronized internal fun writeReceiptOutbox(value: String): Boolean {
        require(value.length<=262144)
        return sharedPreferences.edit().putString("play_receipt_outbox_v1",value).commit()
    }
    companion object {
        private const val KEY_WATCH_PROGRESS="watch_progress_v1"
        private const val KEY_PENDING_LOGOUT="pending_session_revocations"
        private const val KEY_ACCESS_TOKEN = "jwt_access_token"
        private const val KEY_REFRESH_TOKEN = "jwt_refresh_token"
    }
}




