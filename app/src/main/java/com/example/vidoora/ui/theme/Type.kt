package com.example.vidoora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int,line: Int,weight: FontWeight=FontWeight.Normal,tracking: Float=0f)=
    TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=weight,fontSize=size.sp,lineHeight=line.sp,letterSpacing=tracking.sp)

val Typography=Typography(
    displaySmall=type(36,44,FontWeight.Bold,-.6f),
    headlineLarge=type(32,40,FontWeight.Bold,-.5f),
    headlineMedium=type(30,38,FontWeight.Bold,-.4f),
    headlineSmall=type(28,36,FontWeight.Bold,-.4f),
    titleLarge=type(22,30,FontWeight.SemiBold,-.2f),
    titleMedium=type(16,24,FontWeight.SemiBold),
    titleSmall=type(14,20,FontWeight.SemiBold),
    bodyLarge=type(16,24),bodyMedium=type(14,22),bodySmall=type(13,20),
    labelLarge=type(14,20,FontWeight.SemiBold),labelMedium=type(13,18,FontWeight.Medium),labelSmall=type(12,16,FontWeight.Medium)
)
