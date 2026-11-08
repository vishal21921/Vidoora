package com.example.vidoora.feature.membership

import org.junit.Assert.*
import org.junit.Test
import com.android.billingclient.api.ProductDetails

class PlayAccountBindingTest {
    @Test fun bindingIsCanonicalAndAccountSpecificWithoutPersonalData() {
        val account = "ABCDEF00-0000-0000-0000-000000000001"
        val binding = playAccountBinding(account)
        assertEquals(playAccountBinding(account.lowercase()), binding)
        assertEquals(64, binding.length)
        assertTrue(binding.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(binding, playAccountBinding("abcdef00-0000-0000-0000-000000000002"))
    }
    @Test(expected = IllegalArgumentException::class) fun emailIsNotAValidBillingIdentity() {
        playAccountBinding("fixture@example.test")
    }
    @Test fun onlyApprovedInrPricesAndDurationsArePurchasable() {
        val once = ProductDetails.RecurrenceMode.NON_RECURRING
        val recurring = ProductDetails.RecurrenceMode.INFINITE_RECURRING
        assertTrue(approvedPlayPrice("DAY", "INR", 13_000_000, "P1D", once))
        assertTrue(approvedPlayPrice("WEEK", "INR", 39_000_000, "P1W", once))
        assertTrue(approvedPlayPrice("MONTHLY", "INR", 99_000_000, "P1M", recurring))
        assertFalse(approvedPlayPrice("DAY", "INR", 13_000_000, "P1D", recurring))
        assertFalse(approvedPlayPrice("WEEK", "INR", 390_000_000, "P1W", once))
        assertFalse(approvedPlayPrice("MONTHLY", "USD", 99_000_000, "P1M", recurring))
        assertFalse(approvedPlayPrice("MONTHLY", "INR", 99_000_000, "P30D", recurring))
    }
}
