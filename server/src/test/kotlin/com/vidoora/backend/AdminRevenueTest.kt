package com.vidoora.backend

import com.vidoora.backend.modules.analytics.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AdminRevenueTest {
    @Test fun cycleCrossesMonthsAndYearsAndHandlesLeapFebruary() {
        fun check(day: String,start: String,end: String) {
            val cycle=AdminReportingCycle.containing(LocalDate.parse(day))
            assertEquals(LocalDate.parse(start),cycle.start);assertEquals(LocalDate.parse(end),cycle.end)
        }
        check("2026-11-07","2026-10-15","2026-11-14")
        check("2026-11-14","2026-10-15","2026-11-14")
        check("2026-11-15","2026-11-15","2026-12-14")
        check("2027-01-01","2026-12-15","2027-01-14")
        check("2028-02-29","2028-02-15","2028-03-14")
    }
    @Test fun rollOverUsesIndiaMidnightAndUnavailableDoesNotBecomeZero() {
        val before=Clock.fixed(Instant.parse("2026-11-14T18:29:59Z"),ZoneOffset.UTC)
        val after=Clock.fixed(Instant.parse("2026-11-14T18:30:00Z"),ZoneOffset.UTC)
        val a=AdminRevenue.unavailable(before,"fixture")
        val b=AdminRevenue.unavailable(after,"fixture")
        assertEquals("2026-10-15",a.periodStart);assertEquals("2026-11-15",b.periodStart)
        assertFalse(b.connected)
        assertNull(b.monthMinor);assertNull(b.todayMinor);assertNull(b.yesterdayMinor);assertNull(b.weekMinor)
    }
}
