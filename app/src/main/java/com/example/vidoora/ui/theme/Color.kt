package com.example.vidoora.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Reference artwork colors; action stops are darkened to retain readable white button labels. */
object VidooraColors {
    val Background=Color(0xFF070B17)
    val SecondaryBackground=Color(0xFF0D1528)
    val Elevated=Color(0xFF142039)
    val Text=Color(0xFFF8FAFF)
    val Muted=Color(0xFF9BAAC5)
    val Coral=Color(0xFFFF7849)
    val Pink=Color(0xFFFF315B)
    val Violet=Color(0xFF8B3DFF)
    val Lavender=Color(0xFFC0A5FF)
    val Success=Color(0xFF36D49A)
    val Warning=Color(0xFFFFB84D)
    val Error=Color(0xFFFF5267)
    val Border=Color(0xFF2A3A54)
    val ActionCoral=Color(0xFFB83E23)
    val ActionPink=Color(0xFFC11F4F)
    val ActionViolet=Color(0xFF8022C4)
    val ActionText=Color.White
    val ActionGradient=listOf(ActionCoral,ActionPink,ActionViolet)
}

object VidooraSpacing {
    val Small=8.dp
    val Medium=12.dp
    val Content=16.dp
    val Section=24.dp
    val Page=20.dp
    val TouchTarget=48.dp
    val ButtonHeight=52.dp
    val CardRadius=18.dp
}

// Retained source-compatible aliases; new UI uses the named brand tokens above.
val Purple80=VidooraColors.Lavender
val PurpleGrey80=VidooraColors.Muted
val Pink80=Color(0xFFFFB4C4)
val Purple40=VidooraColors.ActionViolet
val PurpleGrey40=Color(0xFF53617A)
val Pink40=VidooraColors.ActionPink
