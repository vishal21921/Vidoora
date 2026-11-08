package com.example.vidoora.feature.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vidoora.core.security.SecureStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(val client: HttpClient,val shareAccess: com.example.vidoora.core.network.VideoShareAccess,
    val progress: WatchProgressStore,private val storage: SecureStorageManager,val history: WatchHistoryStore,
    val analytics: com.example.vidoora.feature.analytics.PlaybackAnalytics) : ViewModel() {
    val identity=storage.sessionChanges.map { storage.watchIdentity() }.distinctUntilChanged()
        .stateIn(viewModelScope,SharingStarted.Eagerly,storage.watchIdentity())
    fun currentIdentity(): String?=storage.watchIdentity()
}
