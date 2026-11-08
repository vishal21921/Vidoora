package com.example.vidoora.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun VidooraGradientButton(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,
                          loading: Boolean=false,@DrawableRes icon: Int?=null) {
    val active=enabled && !loading
    val stops=if(active) VidooraColors.ActionGradient else List(2) { MaterialTheme.colorScheme.surfaceContainerHighest }
    Surface(onClick=onClick,enabled=active,shape=MaterialTheme.shapes.small,color=Color.Transparent,
        contentColor=if(active) VidooraColors.ActionText else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=modifier.fillMaxWidth().heightIn(min=VidooraSpacing.ButtonHeight).semantics {
            if(loading) { contentDescription=text; stateDescription="Loading" }
        }) {
        Box(Modifier.background(Brush.linearGradient(stops)).padding(horizontal=20.dp,vertical=14.dp),contentAlignment=Alignment.Center) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(loading) CircularProgressIndicator(Modifier.size(20.dp),color=MaterialTheme.colorScheme.onSurface,strokeWidth=2.dp)
                else if(icon!=null) UiIcon(icon,modifier=Modifier.size(20.dp))
                Text(text,style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.Center,modifier=Modifier.weight(1f,fill=false))
            }
        }
    }
}

@Composable
fun VidooraOutlinedButton(text: String,onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,@DrawableRes icon: Int?=null) {
    OutlinedButton(onClick=onClick,enabled=enabled,shape=MaterialTheme.shapes.small,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),
        colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),
        modifier=modifier.fillMaxWidth().heightIn(min=VidooraSpacing.ButtonHeight),contentPadding=PaddingValues(horizontal=20.dp,vertical=14.dp)) {
        if(icon!=null) { UiIcon(icon,modifier=Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text,style=MaterialTheme.typography.labelLarge,textAlign=TextAlign.Center,modifier=Modifier.weight(1f,fill=false))
    }
}

@Composable
fun VidooraCard(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier=modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor=MaterialTheme.colorScheme.onSurface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(content=content)
    }
}

/** Callers own values, validation, keyboard actions and password state. */
@Composable
fun VidooraTextField(value: String,onValueChange: (String)->Unit,label: String,modifier: Modifier=Modifier,
                     enabled: Boolean=true,isError: Boolean=false,singleLine: Boolean=true,supportingText: String?=null,
                     leadingIcon: (@Composable ()->Unit)?=null,trailingIcon: (@Composable ()->Unit)?=null,
                     visualTransformation: VisualTransformation=VisualTransformation.None,
                     keyboardOptions: KeyboardOptions=KeyboardOptions.Default,keyboardActions: KeyboardActions=KeyboardActions.Default) {
    OutlinedTextField(value=value,onValueChange=onValueChange,label={ Text(label) },
        modifier=modifier.fillMaxWidth().heightIn(min=56.dp),enabled=enabled,isError=isError,singleLine=singleLine,
        textStyle=MaterialTheme.typography.bodyLarge,shape=MaterialTheme.shapes.small,
        supportingText=if(supportingText!=null) ({ Text(supportingText) }) else null,
        leadingIcon=leadingIcon,trailingIcon=trailingIcon,visualTransformation=visualTransformation,
        keyboardOptions=keyboardOptions,keyboardActions=keyboardActions,
        colors=OutlinedTextFieldDefaults.colors(
            focusedContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor=MaterialTheme.colorScheme.primary,unfocusedBorderColor=MaterialTheme.colorScheme.outline))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VidooraTopBar(title: String,onBack: (() -> Unit)?=null,actions: @Composable RowScope.()->Unit={}) {
    Column {
        TopAppBar(title={ Text(title,style=MaterialTheme.typography.titleLarge) },
            navigationIcon={ if(onBack!=null) IconButton(onClick=onBack) { UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") } },
            actions=actions,colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f))
    }
}

enum class VidooraStatusTone { Neutral,Info,Success,Warning,Error }

@Composable
fun VidooraStatusChip(text: String,tone: VidooraStatusTone=VidooraStatusTone.Neutral,@DrawableRes icon: Int?=null) {
    val colors=MaterialTheme.colorScheme
    val color=when(tone) {
        VidooraStatusTone.Neutral -> colors.onSurfaceVariant
        VidooraStatusTone.Info -> colors.secondary
        VidooraStatusTone.Success -> colors.tertiary
        VidooraStatusTone.Warning -> if(colors.surface.luminance()<.5f) VidooraColors.Warning else Color(0xFF885000)
        VidooraStatusTone.Error -> colors.error
    }
    Surface(shape=RoundedCornerShape(8.dp),color=color.copy(alpha=.12f),contentColor=color) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
            if(icon!=null) UiIcon(icon,modifier=Modifier.size(14.dp))
            Text(text,style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun VidooraLoadingState(label: String="Loading",modifier: Modifier=Modifier) {
    Row(modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.primary)
        Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VidooraEmptyState(title: String,message: String,@DrawableRes icon: Int,modifier: Modifier=Modifier,action: (@Composable ()->Unit)?=null) {
    Column(modifier.readableWidth(400.dp).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.size(56.dp)) {
            Box(contentAlignment=Alignment.Center) { UiIcon(icon,modifier=Modifier.size(28.dp)) }
        }
        Text(title,style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
        Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
        action?.invoke()
    }
}

