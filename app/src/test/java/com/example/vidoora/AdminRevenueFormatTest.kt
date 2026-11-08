package com.example.vidoora

import com.example.vidoora.feature.analytics.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdminRevenueFormatTest {
    private val disconnected=AdminRevenueView("2026-10-15","2026-11-14","2026-11-07T12:00:00Z")
    @Test fun unavailableIncomeStaysUnavailableEvenIfAnAmountIsPresent() {
        assertEquals("—",adminMoney(1300,disconnected))
        assertEquals("—",adminMoney(null,disconnected.copy(connected=true)))
        assertEquals("₹13",adminMoney(1300,disconnected.copy(connected=true)))
        assertEquals("₹13.99",adminMoney(1399,disconnected.copy(connected=true)))
        assertEquals("₹0",adminMoney(0,disconnected.copy(connected=true)))
    }
    @Test fun monthlyDisplayUsesFifteenthAcrossYearBoundary() {
        assertEquals(LocalDate.parse("2026-12-15"),adminCycleStart(LocalDate.parse("2027-01-14")))
        assertEquals(LocalDate.parse("2027-01-15"),adminCycleStart(LocalDate.parse("2027-01-15")))
    }
}
