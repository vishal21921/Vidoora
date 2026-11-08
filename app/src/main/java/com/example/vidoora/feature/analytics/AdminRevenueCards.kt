package com.example.vidoora.feature.analytics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@Serializable data class AdminRevenueView(val periodStart: String,val periodEnd: String,val asOf: String,
    val currency: String="INR",val monthMinor: Long?=null,val todayMinor: Long?=null,
    val yesterdayMinor: Long?=null,val weekMinor: Long?=null,val connected: Boolean=false,
    val source: String="Revenue reporting not connected",val timezone: String="Asia/Kolkata")

internal fun adminCycleStart(today: LocalDate): LocalDate =
    if(today.dayOfMonth>=15) today.withDayOfMonth(15) else today.minusMonths(1).withDayOfMonth(15)

internal fun adminMoney(value: Long?,data: AdminRevenueView?): String {
    if(data?.connected!=true || value==null) return "—"
    return runCatching {
        val currency=Currency.getInstance(data.currency)
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
            this.currency=currency; minimumFractionDigits=0;maximumFractionDigits=currency.defaultFractionDigits
        }.format(BigDecimal.valueOf(value).movePointLeft(currency.defaultFractionDigits))
    }.getOrDefault("—")
}

/** Shared admin summary. Unavailable financial reporting must never look like earned money. */
@Composable fun AdminRevenueCards(data: AdminRevenueView?) {
    val colors=listOf(Color(0xFFFF8068),Color(0xFF9A77F5))
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),
            border=BorderStroke(1.dp,Brush.linearGradient(colors)),color=MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.background(Brush.linearGradient(colors.map {it.copy(alpha=.09f)}))
                .padding(horizontal=12.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("This Month's Earnings",fontSize=15.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(adminMoney(data?.monthMinor,data),fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold)
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            for((label,value) in listOf("Today" to data?.todayMinor,"Yesterday" to data?.yesterdayMinor,"This Week" to data?.weekMinor)) {
                Surface(Modifier.weight(1f),shape=RoundedCornerShape(9.dp),
                    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(horizontal=9.dp,vertical=6.dp)) {
                        Text(label,fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(adminMoney(value,data),fontSize=16.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
