package com.example.vidoora.feature.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vidoora.ui.theme.ReferenceCard
import com.example.vidoora.ui.theme.readableWidth
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable data class ServiceHealthResult(val checkedAt: String, val processor: String,
    val processorLastSeen: String? = null, val lastVerifiedBackup: String? = null,
    val queued: Long, val processing: Long, val failed: Long, val stuck: Long)

@Composable fun ServiceHealthScreen(model: CreatorAnalyticsViewModel, onBack: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var health by remember { mutableStateOf<ServiceHealthResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        health = null
        failed = false
        try { health = model.serviceHealth() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Scaffold(topBar = { AdminCompactHeader("Service Health", onBack, { revision++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(680.dp),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                failed -> item {
                    Text("Service health unavailable")
                    TextButton({ revision++ }) { Text("Retry") }
                }
                health == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            health?.let { result ->
                item { HealthRow("API", "Responding") }
                item { HealthRow("Database", "Connected") }
                item { HealthRow("Processor", when (result.processor) {
                    "RECENT" -> "Heartbeat received"
                    "STALE" -> "Heartbeat overdue"
                    else -> "Unknown"
                }, result.processorLastSeen) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { HealthRow("Queued", result.queued.toString()) }
                    Column(Modifier.weight(1f)) { HealthRow("Processing", result.processing.toString()) }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { HealthRow("Failed", result.failed.toString()) }
                    Column(Modifier.weight(1f)) { HealthRow("Expired leases", result.stuck.toString()) }
                } }
                item { HealthRow("Last verified backup", result.lastVerifiedBackup?.let(::healthTime) ?: "Unknown") }
                item { Text("Checked ${healthTime(result.checkedAt)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun HealthRow(label: String, value: String, time: String? = null) {
    ReferenceCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
            time?.let { Text(healthTime(it), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun healthTime(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss"))
}.getOrDefault("Unknown")
