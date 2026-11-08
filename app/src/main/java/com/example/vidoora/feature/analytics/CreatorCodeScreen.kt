package com.example.vidoora.feature.analytics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.vidoora.ui.theme.readableWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable fun CreatorCodeScreen(model: CreatorAnalyticsViewModel,onBack: ()->Unit) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var code by remember { mutableStateOf<CreatorCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { code=null; generation++; busy=false }
    LaunchedEffect(code) {
        val value=code ?: return@LaunchedEffect
        while(code==value) {
            seconds=java.time.Duration.between(Instant.now(),Instant.parse(value.expiresAt)).seconds.coerceAtLeast(0)
            if(seconds==0L) {code=null;break}
            delay(1000)
        }
    }
    Scaffold(topBar={AdminCompactHeader("Creator invite code",onBack)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth(600.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("ADMIN",color=MaterialTheme.colorScheme.primary)
            Button({
                if(!busy) {
                    busy=true;error=false;code=null
                    val request=++generation
                    scope.launch {
                        try { val generated=model.createCreatorCode(); if(generation==request) code=generated }
                        catch(e: CancellationException) {throw e} catch(_: Exception) {if(generation==request) error=true}
                        finally {if(generation==request) busy=false}
                    }
                }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Generating…" else "Generate code")}
            if(error) Text("Couldn't generate code. Try again.",color=MaterialTheme.colorScheme.error)
            code?.let { value ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(value.code,style=MaterialTheme.typography.titleMedium)
                        Text("Expires in ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}")
                        OutlinedButton({
                            val clip=ClipData.newPlainText("Creator invite",value.code)
                            clip.description.extras=PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        },Modifier.fillMaxWidth(),enabled=seconds>0) {Text("Copy code")}
                    }
                }
            }
        }
    }
}
