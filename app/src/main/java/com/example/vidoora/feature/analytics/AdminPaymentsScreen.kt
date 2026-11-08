package com.example.vidoora.feature.analytics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.ReferenceHeader
import com.example.vidoora.ui.theme.UiIcon
import com.example.vidoora.ui.theme.readableWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable data class AdminPaymentsResult(val day: Long, val week: Long, val month: Long,
    val items: List<AdminPaymentItem>, val hasNext: Boolean, val earnings: AdminRevenueView?=null)
@Serializable data class AdminPaymentItem(val reference: String, val userId: String, val plan: String?,
    val status: String, val activation: String, val time: String, val expiry: String?, val updatedAt: String)

private val statuses = listOf("ALL" to "All Status", "SUCCESSFUL" to "Successful", "PENDING" to "Pending",
    "FAILED" to "Failed", "REFUNDED" to "Refunded", "EXPIRED" to "Expired", "REVIEW" to "Needs review")
private fun paymentTime(value: String?) = value?.let {
    runCatching { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"))
        .format(Instant.parse(it)) }.getOrNull()
} ?: "—"
private fun paymentPlan(value: String?) = when (value) {
    "DAY" -> "Day Pass"
    "WEEK" -> "Week Pass"
    "MONTHLY" -> "Monthly Plan"
    else -> "Awaiting verification"
}
private fun paymentColor(status: String) = when (status) {
    "SUCCESSFUL" -> Color(0xFF61DAAE)
    "PENDING" -> Color(0xFFFFD166)
    "FAILED", "REVIEW" -> Color(0xFFFF8585)
    "REFUNDED" -> Color(0xFF83B7FF)
    else -> Color(0xFFBCC4D6)
}

@Composable
fun AdminPaymentsScreen(model: CreatorAnalyticsViewModel, onBack: () -> Unit) {
    var days by rememberSaveable { mutableIntStateOf(-1) }
    var status by rememberSaveable { mutableStateOf("ALL") }
    var search by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<AdminPaymentsResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<AdminPaymentItem?>(null) }
    LaunchedEffect(days, status, search, page, revision) {
        result = null
        failed = false
        if (search.isNotBlank()) delay(250)
        try { result = model.payments(days, status, search, page) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    Scaffold(topBar = {
        AdminCompactHeader("Subscriptions & Payments", onBack, { revision++ })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(720.dp),
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AdminRevenueCards(result?.earnings) }
            item {
                Text("ADMIN", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentCount("Active Day Plans", result?.day, Modifier.weight(1f))
                        PaymentCount("Active Week Plans", result?.week, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentCount("Active Month Plans", result?.month, Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { Text("Transactions", style = MaterialTheme.typography.titleMedium) }
            item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((range,label) in listOf(1 to "Today",-2 to "Yesterday",7 to "7 Days",-1 to "30 Days",0 to "All")) FilterChip(days == range, {
                            days = range
                            page = 0
                        }, { Text(label, fontSize = 12.sp) })
                    }
            }
            item {
                OutlinedTextField(search, { search = it.take(128); page = 0 }, Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text("Search by User ID or TXN ID", fontSize = 13.sp) },
                    shape = RoundedCornerShape(10.dp))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((key, label) in statuses) FilterChip(status == key, { status = key; page = 0 },
                        { Text(label, fontSize = 11.sp) })
                }
            }
            when {
                failed -> item {
                    Text("Unable to load payments.")
                    TextButton({ revision++ }) { Text("Retry") }
                }
                result == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                result?.items?.isEmpty() == true -> item { Text("No recorded transactions match.") }
            }
            items(result?.items.orEmpty(), key = { it.reference }) { payment ->
                Surface(Modifier.fillMaxWidth().clickable { detail = payment }, shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.padding(10.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("User: ${payment.userId}", Modifier.padding(end=88.dp), fontSize = 12.sp, lineHeight=16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(paymentPlan(payment.plan), fontSize = 12.sp, lineHeight=15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("TXN ID: ${payment.reference}", fontSize = 11.sp, lineHeight=14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Time: ${paymentTime(payment.time)}", fontSize = 11.sp, lineHeight=14.sp)
                            Text("Expiry: ${paymentTime(payment.expiry)}", fontSize = 11.sp, lineHeight=14.sp)
                        }
                        val label = statuses.firstOrNull { it.first == payment.status }?.second ?: payment.status
                        Surface(Modifier.align(Alignment.TopEnd), color = paymentColor(payment.status).copy(alpha = .15f), shape = RoundedCornerShape(8.dp)) {
                            Text(label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = paymentColor(payment.status), fontSize = 10.sp,lineHeight=12.sp)
                        }
                    }
                }
            }
            result?.let { data -> if (page > 0 || data.hasNext) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
                    TextButton({ page++ }, enabled = data.hasNext) { Text("Next") }
                }
            } }
        }
    }
    detail?.let { payment ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(paymentPlan(payment.plan)) },
            text = { Text("User ID: ${payment.userId}\nTXN ID: ${payment.reference}\nPurchase: ${payment.status}\nActivation: ${payment.activation}\nUpdated: ${paymentTime(payment.updatedAt)}") },
            confirmButton = { TextButton({ detail = null }) { Text("Close") } })
    }
}

@Composable
private fun PaymentCount(title: String, count: Long?, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, fontSize = 12.sp)
            Text(count?.toString() ?: "—", style = MaterialTheme.typography.titleMedium)
        }
    }
}
