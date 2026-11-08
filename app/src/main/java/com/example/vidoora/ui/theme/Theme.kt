package com.example.vidoora.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

internal val VidooraLightColors=lightColorScheme(
    primary=VidooraColors.ActionPink,onPrimary=Color.White,
    primaryContainer=Color(0xFFFFE6ED),onPrimaryContainer=Color(0xFF68142F),
    secondary=Color(0xFF6941A5),onSecondary=Color.White,
    secondaryContainer=Color(0xFFF0E8FF),onSecondaryContainer=Color(0xFF33204D),
    tertiary=Color(0xFF006C4A),onTertiary=Color.White,
    tertiaryContainer=Color(0xFFD4F6E6),onTertiaryContainer=Color(0xFF003824),
    background=Color(0xFFFAFBFF),onBackground=Color(0xFF10182D),
    surface=Color(0xFFFAFBFF),onSurface=Color(0xFF10182D),
    surfaceVariant=Color(0xFFE8ECF4),onSurfaceVariant=Color(0xFF53617A),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF3F5FB),surfaceContainer=Color(0xFFEDF0F8),
    surfaceContainerHigh=Color(0xFFE6EAF4),surfaceContainerHighest=Color(0xFFDDE3F0),
    outline=Color(0xFF75829B),outlineVariant=Color(0xFFCDD5E5),
    error=Color(0xFFB51C35),onError=Color.White,errorContainer=Color(0xFFFFE5EA),onErrorContainer=Color(0xFF7C1027)
)
internal val VidooraDarkColors=darkColorScheme(
    primary=VidooraColors.Coral,onPrimary=VidooraColors.Background,
    primaryContainer=Color(0xFF3B1830),onPrimaryContainer=Color(0xFFFFD8E1),
    secondary=VidooraColors.Lavender,onSecondary=Color(0xFF241438),
    secondaryContainer=Color(0xFF281F48),onSecondaryContainer=Color(0xFFF0E7FF),
    tertiary=VidooraColors.Success,onTertiary=Color(0xFF03251C),
    tertiaryContainer=Color(0xFF0D332A),onTertiaryContainer=Color(0xFFA5F6D7),
    background=VidooraColors.Background,onBackground=VidooraColors.Text,
    surface=VidooraColors.Background,onSurface=VidooraColors.Text,
    surfaceVariant=VidooraColors.Elevated,onSurfaceVariant=VidooraColors.Muted,
    surfaceContainerLowest=Color(0xFF040711),surfaceContainerLow=VidooraColors.SecondaryBackground,
    surfaceContainer=Color(0xFF101A30),surfaceContainerHigh=VidooraColors.Elevated,surfaceContainerHighest=Color(0xFF1C2B45),
    outline=Color(0xFF687D9B),outlineVariant=VidooraColors.Border,
    error=VidooraColors.Error,onError=Color(0xFF28040C),errorContainer=Color(0xFF401422),onErrorContainer=Color(0xFFFFD9DF)
)

/** Cinematic dark is the product default. Light and dynamic colors remain explicit opt-ins. */
@Composable
fun VidooraTheme(darkTheme: Boolean=true,dynamicColor: Boolean=false,content: @Composable ()->Unit) {
    val context=LocalContext.current
    val view=LocalView.current
    val colors=when {
        dynamicColor && Build.VERSION.SDK_INT>=Build.VERSION_CODES.S -> if(darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> VidooraDarkColors
        else -> VidooraLightColors
    }
    if(!view.isInEditMode) SideEffect {
        context.themeActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!darkTheme
                isAppearanceLightNavigationBars=!darkTheme
            }
        }
    }
    MaterialTheme(colorScheme=colors,typography=Typography,
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(VidooraSpacing.CardRadius),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}

private tailrec fun Context.themeActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> if(baseContext!==this) baseContext.themeActivity() else null
    else -> null
}
