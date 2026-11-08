package com.example.vidoora.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Centre readable content without stretching fields/cards across a tablet. */
fun Modifier.readableWidth(max: Dp=720.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max=max).fillMaxWidth()
