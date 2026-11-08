package com.example.vidoora.feature.playback

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditVideoDialog(state: VideoDetailsState,onCancel: ()->Unit,onSave: ()->Unit,onTitle: (String)->Unit,onDescription: (String)->Unit) {
    val titleValid=state.editTitle.trim().length in 1..128
    val descriptionValid=state.editDescription.length<=5000
    AlertDialog(onDismissRequest={ if(!state.loading) onCancel() },title={ Text("Edit video details") },text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value=state.editTitle,onValueChange=onTitle,enabled=!state.loading,label={ Text("Title") },
                supportingText={ Text("${state.editTitle.trim().length}/128") },isError=!titleValid,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=state.editDescription,onValueChange=onDescription,enabled=!state.loading,label={ Text("Description (optional)") },
                supportingText={ Text("${state.editDescription.length}/5000") },isError=!descriptionValid,modifier=Modifier.fillMaxWidth())
            state.editError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={ TextButton(onClick=onSave,enabled=!state.loading && titleValid && descriptionValid) { Text("Save") } },
        dismissButton={ TextButton(onClick=onCancel,enabled=!state.loading) { Text("Cancel") } })
}
