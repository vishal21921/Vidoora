package com.example.vidoora.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vidoora.R

/** Synthetic design-time gallery, deliberately absent from application navigation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VidooraDesignSystemGallery() {
    var title by remember { mutableStateOf("") }
    Surface(color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth(600.dp).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Vidoora design system",style=MaterialTheme.typography.headlineSmall)
            Text("Cinematic surfaces. Clear actions. Comfortable reading.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Swatch("Background",VidooraColors.Background)
                Swatch("Surface",VidooraColors.Elevated)
                Swatch("Coral",VidooraColors.Coral)
                Swatch("Pink",VidooraColors.Pink)
                Swatch("Violet",VidooraColors.Violet)
            }
            VidooraCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Shared component foundation",style=MaterialTheme.typography.titleMedium)
                    Text("Real screens keep their existing state, actions and navigation.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VidooraGradientButton("Primary action",{},icon=R.drawable.ic_ui_play)
            VidooraOutlinedButton("Secondary action",{})
            VidooraTextField(title,{title=it},"Video title",supportingText="Preview only. No upload or API call.")
            VidooraTextField("",{},"Validation example",isError=true,supportingText="A clear, readable validation message.")
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                VidooraStatusChip("Ready",VidooraStatusTone.Success)
                VidooraStatusChip("Processing",VidooraStatusTone.Warning)
                VidooraStatusChip("Private",VidooraStatusTone.Neutral,R.drawable.ic_ui_lock)
                VidooraStatusChip("Unavailable",VidooraStatusTone.Error)
            }
            VidooraLoadingState("Indeterminate loading")
            VidooraGradientButton("Busy action",{},loading=true)
            VidooraGradientButton("Disabled action",{},enabled=false)
            FormError("An actionable error remains visible.")
            VidooraEmptyState("Empty state","Existing screens will adopt this layout in their approved stage.",R.drawable.ic_ui_unavailable)
        }
    }
}

@Composable
private fun Swatch(label: String,color: Color) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(42.dp).background(color,MaterialTheme.shapes.small))
        Text(label,style=MaterialTheme.typography.labelSmall)
    }
}

@Preview(name="Cinematic dark",widthDp=360,heightDp=900,showBackground=true)
@Preview(name="Narrow phone",widthDp=320,heightDp=640,showBackground=true)
@Preview(name="Large text",widthDp=360,heightDp=800,fontScale=2f,showBackground=true)
@Preview(name="Wide landscape",widthDp=840,heightDp=400,showBackground=true)
@Composable
private fun CinematicSystemPreview() { VidooraTheme { VidooraDesignSystemGallery() } }

@Preview(name="Explicit light alternative",widthDp=360,heightDp=900,showBackground=true)
@Composable
private fun LightSystemPreview() { VidooraTheme(darkTheme=false) { VidooraDesignSystemGallery() } }

