package com.vidoora.backend.modules.analytics

import kotlinx.serialization.Serializable
import java.time.*

/** Custom reporting dates only. Never changes Google billing periods or entitlement expiry. */
data class AdminReportingCycle(val start: LocalDate, val end: LocalDate) {
    companion object {
        fun containing(date: LocalDate): AdminReportingCycle {
            val start=if(date.dayOfMonth>=15) date.withDayOfMonth(15) else date.minusMonths(1).withDayOfMonth(15)
            return AdminReportingCycle(start,start.plusMonths(1).minusDays(1))
        }
    }
}

/** Null means unavailable, never zero earnings. No receipt-price or impression-count estimates. */
@Serializable data class AdminRevenue(val periodStart: String, val periodEnd: String, val asOf: String,
    val currency: String = "INR", val monthMinor: Long? = null, val todayMinor: Long? = null,
    val yesterdayMinor: Long? = null, val weekMinor: Long? = null, val connected: Boolean = false,
    val source: String, val timezone: String = "Asia/Kolkata") {
    companion object {
        fun unavailable(clock: Clock,source: String): AdminRevenue {
            val cycle=AdminReportingCycle.containing(LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))))
            return AdminRevenue(cycle.start.toString(),cycle.end.toString(),clock.instant().toString(),source=source)
        }
    }
}
