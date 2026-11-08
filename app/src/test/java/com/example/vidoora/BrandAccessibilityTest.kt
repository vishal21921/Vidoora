package com.example.vidoora

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.example.vidoora.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accessibility invariants, not screenshots or fixed expected hex values. */
class BrandAccessibilityTest {
    private fun contrast(a: Color,b: Color): Double {
        val light=maxOf(a.luminance(),b.luminance()).toDouble()
        val dark=minOf(a.luminance(),b.luminance()).toDouble()
        return (light+.05)/(dark+.05)
    }
    @Test fun whiteButtonLabelsStayReadableAcrossTheWholeActionGradient() {
        VidooraColors.ActionGradient.zipWithNext().forEach { (a,b) ->
            for(step in 0..100) assertTrue("Gradient label contrast",contrast(lerp(a,b,step/100f),VidooraColors.ActionText)>=4.5)
        }
    }
    @Test fun primaryAndSupportingTextStayReadableOnAllDarkSurfaces() {
        for(background in listOf(VidooraColors.Background,VidooraColors.SecondaryBackground,VidooraColors.Elevated)) {
            assertTrue(contrast(VidooraColors.Text,background)>=4.5)
            assertTrue(contrast(VidooraColors.Muted,background)>=4.5)
        }
    }
    @Test fun darkThemeActionAndFeedbackColorsStayReadableAsText() {
        for(foreground in listOf(VidooraColors.Coral,VidooraColors.Lavender,VidooraColors.Success,VidooraColors.Warning,VidooraColors.Error)) {
            assertTrue(contrast(foreground,VidooraColors.Background)>=4.5)
        }
    }
}
