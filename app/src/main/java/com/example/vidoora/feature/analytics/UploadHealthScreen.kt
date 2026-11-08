package com.example.vidoora.feature.analytics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.ReferenceCard
import com.example.vidoora.ui.theme.ReferenceHeader
import com.example.vidoora.ui.theme.UiIcon
import com.example.vidoora.ui.theme.readableWidth
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable data class UploadHealthResult(val items: List<UploadHealthItem>, val hasNext: Boolean)
@Serializable data class UploadHealthItem(val id: String, val title: String, val uploader: String,
    val uploaderId: String, val status: String, val elapsedSeconds: Long, val progress: Int?, val reason: String?)

@Composable
fun UploadHealthScreen(model: CreatorAnalyticsViewModel, onBack: () -> Unit) {
    val statuses = listOf("ALL" to "All", "STUCK" to "Stuck", "FAILED" to "Failed",
        "PROCESSING" to "Processing", "QUEUED" to "Queued")
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<UploadHealthResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(filter, page, refresh) {
        result = null
        failed = false
        try {
            result = model.uploadHealth(filter, page)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Scaffold(topBar = {
        ReferenceHeader("Upload Health", onBack) {
            IconButton({ refresh++ }) { UiIcon(R.drawable.ic_ui_refresh, "Refresh upload health") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(680.dp),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in statuses) FilterChip(filter == value, {
                        filter = value
                        page = 0
                    }, { Text(label) })
                }
            }
            val loaded = result
            when {
                failed -> item {
                    Text("Upload health is unavailable. Check your connection and admin access.")
                    TextButton({ refresh++ }) { Text("Retry loading") }
                }
                loaded == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                loaded.items.isEmpty() -> item { Text("No uploads match this status.") }
            }
            items(loaded?.items.orEmpty(), key = { it.id }) { upload ->
                ReferenceCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(upload.title, style = MaterialTheme.typography.titleMedium)
                        Text("${upload.uploader} · ${upload.uploaderId}", style = MaterialTheme.typography.bodyMedium)
                        val label = statuses.firstOrNull { it.first == upload.status }?.second ?: upload.status
                        val elapsed = upload.elapsedSeconds
                        val time = when {
                            elapsed >= 86400 -> "${elapsed / 86400}d ${elapsed / 3600 % 24}h"
                            elapsed >= 3600 -> "${elapsed / 3600}h ${elapsed / 60 % 60}m"
                            elapsed >= 60 -> "${elapsed / 60}m"
                            else -> "${elapsed}s"
                        }
                        Text("$label · $time since queued", style = MaterialTheme.typography.bodySmall,
                            color = if (upload.status in listOf("FAILED", "STUCK")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        upload.progress?.coerceIn(0, 100)?.let { Text("$it% processed", style = MaterialTheme.typography.bodySmall) }
                        upload.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (loaded != null && (page > 0 || loaded.hasNext)) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
                    TextButton({ page++ }, enabled = loaded.hasNext) { Text("Next") }
                }
            }
        }
    }
}
