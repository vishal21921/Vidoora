package com.example.vidoora.feature.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vidoora.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

@Composable
internal fun hasInternetConnection(): Boolean {
    val manager = LocalContext.current.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    return manager.internetAvailable()
}

private fun ConnectivityManager.internetAvailable(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork)
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
internal fun rememberInternetConnection(): androidx.compose.runtime.State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    return produceState(manager?.internetAvailable() ?: true, manager) {
        if (manager == null) return@produceState
        callbackFlow {
            fun publish() { trySend(manager.internetAvailable()) }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
                override fun onLost(network: Network) = publish()
            }
            manager.registerDefaultNetworkCallback(callback)
            publish()
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.conflate().collect { value = it }
    }
}

@Composable
internal fun NoInternetScreen(onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val accent = Brush.horizontalGradient(listOf(Color(0xFFFF886F), Color(0xFFA691E8)))
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF18273B), Color(0xFF090F1D))))) {
        Text("Vidoora", modifier = Modifier.align(Alignment.TopCenter).padding(top = 38.dp),
            style = TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFFE29BB1), Color.White)),
                fontSize = 28.sp, fontWeight = FontWeight.Bold))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Image(painterResource(R.drawable.ic_connection_lost), null, Modifier.size(104.dp, 82.dp))
            Text("No internet connection", color = Color.White, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRetry, shape = CircleShape,
                modifier = Modifier.widthIn(min = 140.dp).border(2.dp, accent, CircleShape)) {
                Text("Retry", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
