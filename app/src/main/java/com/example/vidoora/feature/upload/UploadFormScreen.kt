package com.example.vidoora.feature.upload

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.example.vidoora.ui.theme.readableWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vidoora.ui.theme.VidooraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadFormScreen(
    selectedVideo: SelectedVideoInfo?,
    formState: UploadFormUiState,
    onVideoSelected: (uriString: String, fileName: String, fileSizeBytes: Long, mimeType: String) -> Unit,
    onStartUploadClick: (title: String, description: String?, visibility: String) -> Unit,
    onViewQueueClick: () -> Unit,
    onBackClick: () -> Unit,
    onClearState: () -> Unit
) {
    val context = LocalContext.current
    val pickerScope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val visibility = "PRIVATE"

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pickerScope.launch {
                val details = withContext(Dispatchers.IO) {
                    var fileName = "video.mp4"
                    var fileSize = 0L
                    val contentResolver = context.contentResolver
                    val mimeType = contentResolver.getType(uri) ?: "video/mp4"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                    Triple(fileName, fileSize, mimeType)
                }
                onVideoSelected(uri.toString(), details.first, details.second, details.third)
            }
        }
    }

    LaunchedEffect(formState) {
        if (formState is UploadFormUiState.Success) {
            onClearState()
            onViewQueueClick()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload video", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { com.example.vidoora.ui.theme.UiIcon(com.example.vidoora.R.drawable.ic_ui_back,"Back") }
                },
                actions = {
                    TextButton(onClick = onViewQueueClick) { Text("Upload Queue") }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .readableWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (selectedVideo == null) {
                            Text(
                                text = "Select a video from your device",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick = { videoPickerLauncher.launch("video/*") }) {
                                Text("Choose Video File")
                            }
                        } else {
                            Text(
                                text = "File: ${selectedVideo.fileName}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val sizeInMB = "%.2f MB".format(selectedVideo.fileSizeBytes.toDouble() / (1024 * 1024))
                            Text(
                                text = "Size: $sizeInMB (${selectedVideo.mimeType})",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!selectedVideo.isValidSize) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Choose a video smaller than 1 GiB.",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(shape=androidx.compose.material3.MaterialTheme.shapes.small,onClick = { videoPickerLauncher.launch("video/*") }) {
                                Text("Change File")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        if (formState is UploadFormUiState.Error) onClearState()
                    },
                    label = { Text("Video Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        if (formState is UploadFormUiState.Error) onClearState()
                    },
                    label = { Text("Description (Optional)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Visibility",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text("Private. After processing, you can enable sharing with a video link.")

                if (formState is UploadFormUiState.Error) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(shape=androidx.compose.material3.MaterialTheme.shapes.small,
                    onClick = { onStartUploadClick(title, description, visibility) },
                    enabled = selectedVideo != null && selectedVideo.isValidSize && formState !is UploadFormUiState.Submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    if (formState is UploadFormUiState.Submitting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text("Start Upload", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun UploadFormScreenPreview() {
    VidooraTheme {
        UploadFormScreen(
            selectedVideo = SelectedVideoInfo(
                uriString = "content://media/external/video/media/101",
                fileName = "my_awesome_video.mp4",
                fileSizeBytes = 52428800L,
                mimeType = "video/mp4",
                isValidSize = true
            ),
            formState = UploadFormUiState.Idle,
            onVideoSelected = { _, _, _, _ -> },
            onStartUploadClick = { _, _, _ -> },
            onViewQueueClick = {},
            onBackClick = {},
            onClearState = {}
        )
    }
}


