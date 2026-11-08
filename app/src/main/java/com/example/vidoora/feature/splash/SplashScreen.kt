package com.example.vidoora.feature.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.VidooraColors


@Composable
fun SplashScreen(state: SplashState,onAuthenticated: ()->Unit,onUnauthenticated: ()->Unit) {
    var elapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Animatable(0f).animateTo(1f,tween(2000,easing=LinearEasing));elapsed=true }
    val authenticated by rememberUpdatedState(onAuthenticated)
    val unauthenticated by rememberUpdatedState(onUnauthenticated)
    LaunchedEffect(state,elapsed) {
        if(elapsed) when(state) {
            SplashState.Authenticated->authenticated()
            SplashState.Unauthenticated->unauthenticated()
            SplashState.Initial->Unit
        }
    }
    val movement by rememberInfiniteTransition(label="Splash loading").animateFloat(-.3f,1f,
        infiniteRepeatable(tween(1400,easing=LinearEasing)),label="Loading segment")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.drawable.vidoora_launcher_art),"Vidoora",Modifier.size(160.dp))
            Text("Vidoora",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.onBackground)
            BoxWithConstraints(Modifier.width(200.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics {
                contentDescription="Loading Vidoora";progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
            }) {
                Box(Modifier.offset(x=maxWidth*movement).width(maxWidth*.3f).fillMaxHeight().background(Brush.horizontalGradient(VidooraColors.ActionGradient)))
            }
        }
    }
}
