package com.example.vidoora

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.vidoora.core.network.SharedVideoLink
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.vidoora.ui.navigation.AppNavGraph
import com.example.vidoora.ui.theme.VidooraTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var incomingLink by mutableStateOf<SharedVideoLink?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        incomingLink=if(intent.action==Intent.ACTION_VIEW) SharedVideoLink.parseIncoming(intent.dataString.orEmpty(),intent.getStringExtra("vidoora_share_token")) else null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingLink=if(intent.action==Intent.ACTION_VIEW) SharedVideoLink.parseIncoming(intent.dataString.orEmpty(),intent.getStringExtra("vidoora_share_token")) else null
        enableEdgeToEdge()
        setContent {
            VidooraTheme {
                AppNavGraph(incomingLink=incomingLink,onIncomingHandled={ incomingLink=null; intent.data=null; intent.removeExtra("vidoora_share_token"); intent.action=Intent.ACTION_MAIN })
            }
        }
    }
}


