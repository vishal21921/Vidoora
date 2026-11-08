package com.example.vidoora.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.vidoora.R

@Composable
fun AccountSection(title: String,content: @Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            modifier=Modifier.padding(start=4.dp))
        ReferenceCard(content=content)
    }
}

@Composable
fun AccountActionRow(title: String,subtitle: String,@DrawableRes icon: Int,enabled: Boolean=true,onClick: ()->Unit) {
    val color=if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest,modifier=Modifier.size(38.dp)) {
            Box(contentAlignment=Alignment.Center) { UiIcon(icon) }
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall,color=color)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(enabled) UiIcon(R.drawable.ic_ui_chevron,modifier=Modifier.size(18.dp))
    }
}

@Composable
fun AccountRowDivider() { HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f)) }
