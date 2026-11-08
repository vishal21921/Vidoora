package com.example.vidoora.feature.analytics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.example.vidoora.R
import com.example.vidoora.core.network.dto.ApiEnvelope
import com.example.vidoora.feature.playback.PrivateThumbnailLoader
import com.example.vidoora.ui.theme.ReferenceHeader
import com.example.vidoora.ui.theme.UiIcon
import com.example.vidoora.ui.theme.readableWidth
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

@Serializable data class CreatorMetrics(val opens: Long, val views: Long, val watchSeconds: Long)
@Serializable data class CreatorDay(val day: String, val metrics: CreatorMetrics)
@Serializable data class CreatorTotals(val totals: CreatorMetrics, val days: List<CreatorDay>)
@Serializable data class UploaderRow(val id: String, val name: String, val email: String?, val active: Boolean,
    val videos: Long, val opens: Long, val views: Long, val watchSeconds: Long)
@Serializable data class VideoRow(val id: String, val title: String, val durationSeconds: Int,
    val thumbnail: Boolean, val shared: Boolean, val opens: Long, val views: Long, val watchSeconds: Long)
@Serializable data class Catalog<T>(val items: List<T>, val total: Long, val videoCount: Long = 0)
@Serializable data class AnalyticsLink(val url: String)

@HiltViewModel
class CreatorAnalyticsViewModel @Inject constructor(private val client: HttpClient): ViewModel() {
    val thumbnails = PrivateThumbnailLoader(client)

    suspend fun report(from: String, to: String): CreatorTotals = read("report", from, to)
    suspend fun serviceHealth(): ServiceHealthResult = read("service-health")
    suspend fun uploaders(from: String, to: String, search: String, sort: String, page: Int): Catalog<UploaderRow> =
        read("uploaders", from, to, search, sort, page)
    suspend fun videos(from: String, to: String, owner: String?, search: String, sort: String, page: Int): Catalog<VideoRow> =
        read("videos", from, to, search, sort, page, owner)
    suspend fun manage(search: String, page: Int): Catalog<UploaderRow> = read("uploaders/manage", search = search, page = page)
    suspend fun remove(id: String) {
        check(client.delete("api/v1/analytics/uploaders/$id").status.value == 204)
    }
    suspend fun link(id: String): String = read<AnalyticsLink>("videos/$id/link").url

    suspend fun uploadHealth(status: String, page: Int): UploadHealthResult =
        read("upload-health", page = page, status = status)
    suspend fun payments(days: Int, status: String, search: String, page: Int): AdminPaymentsResult =
        read("payments", search = search, page = page, status = status, days = days)
    suspend fun adReport(from: String,to: String): AdAnalyticsResult = read("ads",from,to)
    suspend fun createCreatorCode(): CreatorCode {
        val response=client.post("api/v1/analytics/creator-invites")
        check(response.status.value==201)
        return checkNotNull(response.body<ApiEnvelope<CreatorCode>>().data)
    }

    private suspend inline fun <reified T> read(path: String, from: String? = null, to: String? = null,
        search: String? = null, sort: String? = null, page: Int? = null, owner: String? = null,
        status: String? = null, days: Int? = null): T {
        val response = client.get("api/v1/analytics/$path") {
            parameter("from", from)
            parameter("to", to)
            parameter("search", search)
            parameter("sort", sort)
            parameter("page", page)
            parameter("creator", owner)
            parameter("status", status)
            parameter("days", days)
        }
        check(response.status.value == 200)
        return checkNotNull(response.body<ApiEnvelope<T>>().data)
    }
}

private val today get() = LocalDate.now(ZoneId.of("Asia/Kolkata"))
private val accent = listOf(Color(0xFF9266F2), Color(0xFFFF8068))
private val cardShape = RoundedCornerShape(12.dp)

@Composable
fun CreatorAnalyticsScreen(model: CreatorAnalyticsViewModel, admin: Boolean, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<UploaderRow?>(null) }
    val detail = selected
    BackHandler(detail != null) { selected = null }
    var range by rememberSaveable { mutableIntStateOf(7) }
    var from by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var to by rememberSaveable { mutableStateOf(today.toString()) }
    var custom by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<CreatorTotals?>(null) }
    var summary by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var summaryError by remember { mutableStateOf(false) }
    LaunchedEffect(from, to, admin, retry) {
        report = null
        summaryError = false
        try {
            val result = model.report(from, to)
            val common = listOf("Total Views" to count(result.totals.views), "Total Watch Time" to watchTime(result.totals.watchSeconds))
            summary = if (admin) {
                val roster = model.uploaders(from, to, "", "views", 0)
                listOf("Total Uploaders" to count(roster.total), "Total Videos" to count(roster.videoCount)) + common
            } else {
                val videos = model.videos(from, to, null, "", "views", 0)
                listOf("My Videos" to count(videos.total)) + common +
                    ("Top Video" to (videos.items.firstOrNull()?.let { "${count(it.views)} views" } ?: "—"))
            }
            report = result
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { summaryError = true }
    }

    val directory = admin && detail == null
    val title = when {
        directory -> "Video Analytics"
        admin -> "Uploader Videos"
        else -> "My Video Analytics"
    }
    val sorts = when {
        directory -> listOf("views" to "Most Views", "videos" to "Most Videos", "watch" to "Watch Time", "recent" to "Recently Active")
        admin -> listOf("views" to "Most Viewed", "all" to "All Videos", "watch" to "Most Watch Time", "recent" to "Newest")
        else -> listOf("views" to "Most Viewed", "watch" to "Most Watch Time", "recent" to "Latest")
    }
    val savedLists = rememberSaveableStateHolder()
    savedLists.SaveableStateProvider(if (detail == null) "overview" else "uploader-videos") {
        var search by rememberSaveable { mutableStateOf("") }
        var sort by rememberSaveable { mutableStateOf("views") }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var uploaders by remember { mutableStateOf<Catalog<UploaderRow>?>(null) }
        var videos by remember { mutableStateOf<Catalog<VideoRow>?>(null) }
        var listError by remember { mutableStateOf(false) }
        LaunchedEffect(from, to, search, sort, page, retry) {
            uploaders = null
            videos = null
            listError = false
            if (search.isNotEmpty()) delay(250)
            try {
                if (directory) uploaders = model.uploaders(from, to, search, sort, page)
                else videos = model.videos(from, to, detail?.id, search, sort, page)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { listError = true }
        }
        Scaffold(topBar = {
            ReferenceHeader(title, { if (detail != null) selected = null else onBack() }) {
                if (directory) Text("ADMIN", Modifier.background(Brush.horizontalGradient(accent), cardShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp), color = Color(0xFF120D20), style = MaterialTheme.typography.labelSmall)
                IconButton({ retry++ }) { UiIcon(R.drawable.ic_ui_refresh, "Refresh analytics") }
            }
        }) { padding ->
            val restoredList = com.example.vidoora.ui.theme.rememberRestoringListState(
                !listError && (if(directory) uploaders != null else videos != null), "$from|$to|$search|$sort|$page")
            LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(760.dp), state = restoredList,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (detail != null) item { UploaderCard(detail) }
                if (detail == null) {
                    report?.let { item { MetricCards(summary) } }
                    item {
                        val ranges = if (admin) listOf("1" to "Today", "7" to "7 Days", "30" to "30 Days", "0" to "Custom")
                        else listOf("7" to "7 Days", "30" to "30 Days", "90" to "90 Days", "0" to "Custom")
                        Chips(ranges, range.toString()) { value ->
                            val days = value.toInt()
                            if (days == 0) custom = true else {
                                range = days
                                from = today.minusDays(days.toLong() - 1).toString()
                                to = today.toString()
                                page = 0
                            }
                        }
                    }
                    if (summaryError) item { LoadError { retry++ } }
                    else if (report == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    report?.let { data -> item { ViewsTrend(from, to, data) } }
                }
                item {
                    SearchField(search, if (directory) "Search uploader name or user ID" else if (admin) "Search videos by name or ID" else "Search my videos") {
                        search = it
                        page = 0
                    }
                }
                item { Chips(sorts, sort) { sort = it; page = 0 } }
                val total = uploaders?.total ?: videos?.total
                if (listError) item { LoadError { retry++ } }
                else if (total == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (total == 0L) item { Text(if (search.isBlank()) "Nothing to show yet." else "No matching results.") }
                items(uploaders?.items.orEmpty(), key = { it.id }) { uploader ->
                    UploaderCard(uploader) {
                        savedLists.removeState("uploader-videos")
                        selected = uploader
                    }
                }
                items(videos?.items.orEmpty(), key = { it.id }) { VideoCard(it, if (admin) model else null) }
                if (total != null && (total > 30 || page > 0)) item { Pages(page, total) { page = it } }
                if (detail == null) item {
                    Text("A view counts after 30 seconds watched, or 90% of a shorter video. Reports use India time and retain up to 90 days.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (custom) DateRangeDialog(from, to, { custom = false }) { start, end ->
        from = start
        to = end
        range = 0
        custom = false
        retry++
    }
}

@Composable
private fun DateRangeDialog(from: String, to: String, close: () -> Unit, apply: (String, String) -> Unit) {
    var start by remember { mutableStateOf(from) }
    var end by remember { mutableStateOf(to) }
    val valid = runCatching {
        val first = LocalDate.parse(start)
        val last = LocalDate.parse(end)
        !first.isBefore(today.minusDays(89)) && !last.isAfter(today) && !first.isAfter(last)
    }.getOrDefault(false)
    AlertDialog(onDismissRequest = close, title = { Text("Custom dates") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose dates in the last 90 days (YYYY-MM-DD).")
            OutlinedTextField(start, { start = it.take(10) }, label = { Text("From") }, singleLine = true)
            OutlinedTextField(end, { end = it.take(10) }, label = { Text("To") }, singleLine = true)
        }
    }, confirmButton = { TextButton({ apply(start, end) }, enabled = valid) { Text("Apply") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}

@Composable
private fun MetricCards(cards: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (pair in cards.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in pair) Surface(Modifier.weight(1f).border(1.dp, Brush.horizontalGradient(accent), cardShape),
                shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun ViewsTrend(from: String, to: String, report: CreatorTotals) {
    val values = remember(from, to, report.days) {
        val byDay = report.days.associate { it.day to it.metrics.views }
        val end = LocalDate.parse(to)
        generateSequence(LocalDate.parse(from)) { it.plusDays(1).takeUnless { next -> next.isAfter(end) } }
            .map { byDay[it.toString()] ?: 0L }.toList()
    }
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Views trend", style = MaterialTheme.typography.titleMedium)
            Text("${count(report.totals.opens)} opens in this period", style = MaterialTheme.typography.bodySmall)
            Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                for (line in 0..4) {
                    val y = size.height * line / 4
                    drawLine(grid, Offset(0f, y), Offset(size.width, y))
                }
                val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
                val points = values.mapIndexed { index, value ->
                    Offset(size.width * index / (values.size - 1).coerceAtLeast(1), size.height * (.95f - .85f * value / max))
                }
                val first = points.firstOrNull() ?: return@Canvas
                if (points.size == 1) drawCircle(accent.last(), 4.dp.toPx(), first)
                else {
                    val line = Path().apply {
                        moveTo(first.x, first.y)
                        for ((left, right) in points.zipWithNext()) {
                            val middle = (left.x + right.x) / 2
                            cubicTo(middle, left.y, middle, right.y, right.x, right.y)
                        }
                    }
                    val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                    drawPath(area, Brush.verticalGradient(listOf(accent.first().copy(alpha = .35f), Color.Transparent)))
                    drawPath(line, Brush.horizontalGradient(accent.reversed()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(from, style = MaterialTheme.typography.labelSmall)
                Text(to, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for ((value, label) in options) FilterChip(selected == value, { onSelect(value) }, { Text(label) })
    }
}

@Composable
private fun UploaderCard(uploader: UploaderRow, open: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClick = open) else Modifier),
        shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                Text(uploader.name.trim().take(1).uppercase().ifEmpty { "?" })
            }
            Column(Modifier.weight(1f)) {
                Text(uploader.name, style = MaterialTheme.typography.titleMedium)
                Text("UID ${uploader.id}", style = MaterialTheme.typography.bodySmall)
                Text(uploader.email ?: "${count(uploader.videos)} videos · ${count(uploader.views)} views · ${watchTime(uploader.watchSeconds)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open == null && uploader.email == null) Text(if (uploader.active) "Active" else "Inactive",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (open != null) UiIcon(R.drawable.ic_ui_chevron)
        }
    }
}

@Composable
private fun VideoCard(video: VideoRow, admin: CreatorAnalyticsViewModel?) {
    var details by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().border(1.dp, Brush.horizontalGradient(accent), cardShape)
        .clickable { details = true }, shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (admin != null) {
                val bitmap by produceState<android.graphics.Bitmap?>(null, video.id, video.thumbnail) {
                    value = if (video.thumbnail) admin.thumbnails.load(video.id, adminAnalytics = true) else null
                }
                Box(Modifier.width(88.dp).height(66.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        ?: UiIcon(R.drawable.ic_ui_video)
                    if (video.durationSeconds > 0) Text(duration(video.durationSeconds),
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.Black.copy(alpha = .78f), RoundedCornerShape(4.dp)).padding(3.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${count(video.views)} views · ${watchTime(video.watchSeconds)}", style = MaterialTheme.typography.bodySmall)
                Text("${count(video.opens)} opens", style = MaterialTheme.typography.labelSmall)
                if (copyError) Text("Link unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (admin != null && video.shared) IconButton(enabled = !loading, onClick = {
                loading = true
                copyError = false
                scope.launch {
                    try {
                        val url = admin.link(video.id)
                        val clip = android.content.ClipData.newPlainText("Vidoora link", url)
                        if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras =
                            android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { copyError = true }
                    finally { loading = false }
                }
            }) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else UiIcon(R.drawable.ic_ui_copy, "Copy video link")
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(video.title) }, text = {
        Text("${count(video.opens)} opens · ${count(video.views)} views\n${watchTime(video.watchSeconds)} watched · ${duration(video.durationSeconds)} duration")
    }, confirmButton = { TextButton({ details = false }) { Text("Close") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveUploaderScreen(model: CreatorAnalyticsViewModel, onBack: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Catalog<UploaderRow>?>(null) }
    var target by remember { mutableStateOf<UploaderRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(search, page, revision) {
        result = null
        error = null
        if (search.isNotBlank()) delay(250)
        try { result = model.manage(search, page) }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { error = "Unable to load uploaders." }
    }
    Scaffold(topBar = { ReferenceHeader("Remove uploader", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(680.dp), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Swipe left to remove uploader access. Videos and account data are kept.") }
            item { SearchField(search, "Search name or user ID") { search = it; page = 0 } }
            error?.let { message -> item { Text(message); TextButton({ revision++ }) { Text("Refresh") } } }
            if (result == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (result?.items?.isEmpty() == true) item { Text("No uploaders found.") }
            items(result?.items.orEmpty(), key = { it.id }) { uploader ->
                var revealed by rememberSaveable { mutableStateOf(false) }
                val swipe = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(swipe, modifier = Modifier.semantics {
                    customActions = listOf(CustomAccessibilityAction("Show remove action") { revealed = true; true })
                }, enableDismissFromStartToEnd = false, onDismiss = {
                    revealed = true
                    scope.launch { swipe.reset() }
                }, backgroundContent = {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, cardShape),
                        contentAlignment = Alignment.CenterEnd) { Text("Remove", Modifier.padding(14.dp)) }
                }) { UploaderCard(uploader) }
                if (revealed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ revealed = false }) { Text("Cancel") }
                    TextButton({ target = uploader }, enabled = !busy) { Text("Remove uploader", color = MaterialTheme.colorScheme.error) }
                }
            }
            result?.total?.let { if (it > 30 || page > 0) item { Pages(page, it) { next -> page = next } } }
        }
    }
    target?.let { uploader ->
        AlertDialog(onDismissRequest = { if (!busy) target = null }, title = { Text("Remove uploader access?") },
            text = { Text("${uploader.name} (${uploader.id}) will lose the Upload tab and Video Analytics. A fresh invite code will restore access to their existing videos and data.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { model.remove(uploader.id); page = 0; revision++ }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { error = "Could not remove access. Refresh and try again." }
                    finally { busy = false; target = null }
                }
            }) { Text("Yes, remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ target = null }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable
private fun SearchField(search: String, label: String, change: (String) -> Unit) {
    OutlinedTextField(search, { change(it.take(128)) }, Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = cardShape)
}

@Composable
private fun LoadError(retry: () -> Unit) {
    Text("Unable to load analytics. Check your connection and access.")
    TextButton(retry) { Text("Retry") }
}

@Composable
private fun Pages(page: Int, total: Long, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton({ change(page - 1) }, enabled = page > 0) { Text("Previous") }
        Text("Page ${page + 1}", style = MaterialTheme.typography.labelMedium)
        TextButton({ change(page + 1) }, enabled = (page + 1L) * 30 < total) { Text("Next") }
    }
}

private fun count(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private fun watchTime(seconds: Long): String = when {
    seconds >= 3600 -> String.format(Locale.US, "%.1f hrs", seconds / 3600.0)
    seconds >= 60 -> String.format(Locale.US, "%.1f min", seconds / 60.0)
    else -> "$seconds sec"
}

private fun duration(seconds: Int) = if (seconds >= 3600)
    String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
