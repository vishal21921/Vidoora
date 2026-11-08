package com.example.vidoora.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.vidoora.R

@Composable
fun UiIcon(@DrawableRes icon: Int, description: String?=null, modifier: Modifier=Modifier.size(22.dp)) {
    Icon(painterResource(icon),contentDescription=description,modifier=modifier)
}

@Composable
fun VidooraBrand(modifier: Modifier=Modifier) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primary,modifier=Modifier.size(26.dp)) {
            Box(contentAlignment=Alignment.Center) { UiIcon(R.drawable.ic_ui_play,modifier=Modifier.size(20.dp)) }
        }
        Text("vidoora",style=MaterialTheme.typography.titleLarge)
    }
}

/** Existing screen APIs delegate to the shared design system; callbacks/state are unchanged. */
@Composable
fun ReferenceHeader(title: String,onBack: (() -> Unit)?=null,actions: @Composable RowScope.()->Unit={}) =
    VidooraTopBar(title,onBack,actions)

@Composable
fun ReferenceCard(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) =
    VidooraCard(modifier,content)

@Composable
fun PrimaryAction(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,loading: Boolean=false,@DrawableRes icon: Int?=null) =
    VidooraGradientButton(text,onClick,modifier,enabled,loading,icon)
@Composable
fun FormError(message: String) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
        Text(message,color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(14.dp))
    }
}

