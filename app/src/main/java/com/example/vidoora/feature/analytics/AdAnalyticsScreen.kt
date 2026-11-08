package com.example.vidoora.feature.analytics

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vidoora.R
import com.example.vidoora.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable data class AdCounts(val format: String, val requests: Long, val loaded: Long, val impressions: Long, val clicks: Long, val failures: Long)
@Serializable data class AdPoint(val time: String, val interstitial: Long, val banner: Long)
@Serializable data class AdActivity(val time: String, val format: String, val event: String)
@Serializable data class AdAnalyticsResult(val from: String, val to: String, val updatedAt: String,
    val formats: List<AdCounts>, val devices: Long, val trend: List<AdPoint>, val recent: List<AdActivity>, val testOnly: Boolean, val source: String,
    val earnings: AdminRevenueView?=null)
@Serializable data class CreatorCode(val code: String, val expiresAt: String) {
    override fun toString()="CreatorCode(<redacted>)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AdminCompactHeader(title: String, onBack: ()->Unit, refresh: (() -> Unit)? = null, onFilter: (() -> Unit)? = null,
    insets: WindowInsets = TopAppBarDefaults.windowInsets) {
    Column {
        TopAppBar(title={Text(title,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)},
            navigationIcon={IconButton(onBack) {UiIcon(R.drawable.ic_ui_back,"Back")}},
            actions={
                if(refresh!=null) IconButton(refresh) {UiIcon(R.drawable.ic_ui_refresh,"Refresh")}
                if(onFilter!=null) IconButton(onFilter) {UiIcon(R.drawable.ic_ui_filter,"Filter ad format")}
            },windowInsets=insets,
            colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f))
    }
}
private val adPurple=Color(0xFF9A77F5)
private val adCoral=Color(0xFFFF8068)
private val adZone=ZoneId.of("Asia/Kolkata")
private fun adNumber(n: Long)=String.format(Locale.US,"%,d",n)
private fun adTime(s: String)=runCatching { DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(adZone).format(Instant.parse(s)) }.getOrDefault("—")

@Composable fun AdAnalyticsScreen(model: CreatorAnalyticsViewModel,onBack: ()->Unit) {
    val today=LocalDate.now(adZone)
    var from by rememberSaveable { mutableStateOf(today.toString()) }
    var to by rememberSaveable { mutableStateOf(today.toString()) }
    var selected by rememberSaveable { mutableStateOf("Today") }
    var format by rememberSaveable { mutableStateOf("All") }
    var showFormats by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<AdAnalyticsResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(from,to,revision) {
        result=null; failed=false
        try { result=model.adReport(from,to) }
        catch(e: CancellationException) { throw e } catch(_: Exception) { failed=true }
    }
    Scaffold(topBar={AdminCompactHeader("Ads Analytics",onBack,{revision++},{showFormats=!showFormats})}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).readableWidth(720.dp),contentPadding=PaddingValues(12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text("ADMIN · TEST ADS",fontSize=11.sp,color=adCoral) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                for(label in listOf("Today","Yesterday","7 Days","30 Days","All")) FilterChip(selected==label,{
                        selected=label
                        val end=if(label=="Yesterday") today.minusDays(1) else today
                        to=end.toString()
                        from=end.minusDays(when(label){"7 Days"->6;"30 Days"->29;"All"->89;else->0}).toString()
                },{Text(label,fontSize=12.sp)})
            } }
            when {
                failed -> item { Text("Unable to load ad activity."); TextButton({revision++}){Text("Retry")} }
                result==null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            result?.let { data ->
                val inter=data.formats.firstOrNull { it.format=="INTERSTITIAL" } ?: AdCounts("INTERSTITIAL",0,0,0,0,0)
                val banner=data.formats.firstOrNull { it.format=="BANNER" } ?: AdCounts("BANNER",0,0,0,0,0)
                val total=inter.impressions+banner.impressions
                item { Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AdMetric("Total Ad Impressions",adNumber(total),Modifier.weight(1f))
                        AdMetric("Interstitial Impressions",adNumber(inter.impressions),Modifier.weight(1f))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AdMetric("Banner Impressions",adNumber(banner.impressions),Modifier.weight(1f))
                        AdMetric("Ad Requests",adNumber(inter.requests+banner.requests),Modifier.weight(1f))
                    }
                    Text("Updated ${adTime(data.updatedAt)}",Modifier.align(Alignment.End),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if(showFormats) item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    for(label in listOf("All","Interstitial","Banner")) FilterChip(format==label,{format=label},{Text(label,fontSize=11.sp)})
                } }
                for(count in listOf(inter,banner).filter { format=="All" || it.format==format.uppercase() }) item {
                    AdPanel {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Surface(color=adPurple.copy(alpha=.15f),shape=RoundedCornerShape(8.dp)) {
                                Box(Modifier.size(42.dp),contentAlignment=Alignment.Center) { Text(if(count.format=="BANNER") "▰" else "▣",color=adPurple,fontSize=24.sp) }
                            }
                            Column {
                                Text(if(count.format=="BANNER") "Banner Ads" else "Interstitial Ads",fontSize=15.sp,lineHeight=18.sp)
                                Text("Impressions: ${adNumber(count.impressions)} | Requests: ${adNumber(count.requests)}",fontSize=11.sp,lineHeight=14.sp)
                                val rate=if(count.requests>0) String.format(Locale.US,"%.1f%%",100.0*count.loaded/count.requests) else "—"
                                Text("Load rate: $rate | Clicks: ${count.clicks} | Failures: ${count.failures}",fontSize=10.sp,lineHeight=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { AdPanel {
                    Text("Ad Impressions Over Time",fontSize=15.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("● Interstitial",fontSize=11.sp,lineHeight=14.sp,color=adPurple)
                        Text("● Banner",fontSize=11.sp,lineHeight=14.sp,color=adCoral)
                    }
                    AdChart(data,format)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(data.from,fontSize=10.sp);Text(data.to,fontSize=10.sp)
                    }
                } }
                item { val avg=if(data.devices>0) String.format(Locale.US,"%.1f",total.toDouble()/data.devices) else "—"
                    AdPanel { Text("Devices Showing Ads: ${adNumber(data.devices)} | Avg: $avg | Android",fontSize=10.sp) }
                }
                item { AdPanel {
                    Text("Recent Activity",fontSize=15.sp)
                    val events=data.recent.filter { format=="All" || it.format==format.uppercase() }
                    if(events.isEmpty()) Text("No recorded activity",fontSize=12.sp)
                    events.forEach { event -> Text("${adTime(event.time)} — ${event.format.lowercase().replaceFirstChar { it.uppercase() }} — ${event.event.lowercase().replace('_',' ')}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
            }
        }
    }
}
@Composable private fun AdMetric(label: String,value: String,modifier: Modifier) {
    Surface(modifier,shape=RoundedCornerShape(9.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal=10.dp,vertical=7.dp)) { Text(label,fontSize=11.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(value,fontSize=19.sp,lineHeight=22.sp) }
    }
}
@Composable private fun AdPanel(content: @Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp,Brush.linearGradient(listOf(adPurple.copy(alpha=.65f),adCoral.copy(alpha=.65f))))) {
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
    }
}
@Composable private fun AdChart(data: AdAnalyticsResult,format: String) {
    val hourly=data.from==data.to
    val first=LocalDate.parse(data.from).atStartOfDay()
    val count=if(hourly) 24 else java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(data.from),LocalDate.parse(data.to)).toInt()+1
    val points=(0 until count).map { index ->
        val date=if(hourly) first.plusHours(index.toLong()) else first.plusDays(index.toLong())
        data.trend.firstOrNull { LocalDateTime.parse(it.time)==date } ?: AdPoint(date.toString(),0,0)
    }
    val max=points.maxOfOrNull { maxOf(it.interstitial,it.banner) }?.coerceAtLeast(1) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical=8.dp)) {
        for(i in 0..4) drawLine(Color.Gray.copy(alpha=.25f),Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4))
        fun line(banner: Boolean,color: Color) {
            val path=Path()
            points.forEachIndexed { i,p ->
                val x=size.width*i/(points.size-1).coerceAtLeast(1)
                val y=size.height*(1f-(if(banner)p.banner else p.interstitial).toFloat()/max)
                if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        if(format!="Banner") line(false,adPurple)
        if(format!="Interstitial") line(true,adCoral)
    }
}
