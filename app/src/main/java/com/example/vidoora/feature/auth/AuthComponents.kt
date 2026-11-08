package com.example.vidoora.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthPage(onBack: (() -> Unit)?,footer: String?=null,content: @Composable ColumnScope.()->Unit) {
    Scaffold(topBar={ TopAppBar(title={ Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
        VidooraBrand(Modifier.padding(end=16.dp))
    } },navigationIcon={ if(onBack!=null) IconButton(onClick=onBack) { UiIcon(R.drawable.ic_ui_back,"Back") } }) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val available=maxHeight
            Column(Modifier.readableWidth(480.dp).verticalScroll(rememberScrollState()).heightIn(min=available).padding(24.dp),
                verticalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(18.dp),content=content)
                if(footer!=null) Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier=Modifier.fillMaxWidth().padding(top=32.dp)) {
                    Text(footer,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier=Modifier.padding(16.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun AuthField(label: String,value: String,onChange: (String)->Unit,icon: Int,password: Boolean=false,
    keyboardType: KeyboardType=KeyboardType.Text,helper: String?=null,enabled: Boolean=true) {
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(label,style=MaterialTheme.typography.labelLarge)
        OutlinedTextField(value=value,onValueChange=onChange,modifier=Modifier.fillMaxWidth().semantics { contentDescription=label },singleLine=true,enabled=enabled,
            leadingIcon={ UiIcon(icon) },shape=RoundedCornerShape(10.dp),
            visualTransformation=if(password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions=KeyboardOptions(keyboardType=keyboardType,imeAction=if(password) ImeAction.Done else ImeAction.Next),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant))
        helper?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

