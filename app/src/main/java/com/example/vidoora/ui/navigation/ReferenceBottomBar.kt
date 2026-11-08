package com.example.vidoora.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*

@Composable
fun ReferenceBottomBar(route: String?,canUpload: Boolean=true,onNavigate: (String)->Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding()
        .padding(horizontal=16.dp,vertical=10.dp),contentAlignment=Alignment.Center) {
        Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
            modifier=Modifier.readableWidth(560.dp)) {
            Row(Modifier.selectableGroup().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf(Triple(NavRoutes.HOME,"Home",R.drawable.ic_ui_home_line),
                    Triple(NavRoutes.HISTORY,"History",R.drawable.ic_ui_history),
                    Triple(NavRoutes.UPLOAD,"Upload",R.drawable.ic_ui_upload_line),
                    Triple(NavRoutes.PROFILE,"Profile",R.drawable.ic_ui_person)).filter { canUpload || it.first!=NavRoutes.UPLOAD }.forEach { (destination,label,icon) ->
                    val selected=route==destination
                    Surface(shape=RoundedCornerShape(18.dp),color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.weight(1f)) {
                        Column(Modifier.selectable(selected=selected,role=Role.Tab,onClick={ onNavigate(destination) })
                            .heightIn(min=64.dp).padding(horizontal=4.dp,vertical=8.dp),
                            verticalArrangement=Arrangement.spacedBy(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            UiIcon(icon,modifier=Modifier.size(22.dp))
                            Text(label,style=MaterialTheme.typography.labelSmall,
                                textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                            Box(Modifier.size(4.dp).background(if(selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,CircleShape))
                        }
                    }
                }
            }
        }
    }
}
