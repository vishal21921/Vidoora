package com.vidoora.backend

import com.vidoora.backend.modules.membership.MembershipRuntime
import io.ktor.server.config.MapApplicationConfig
import org.junit.Assert.*
import org.junit.Test

class InternalCheckoutConfigTest {
    @Test fun checkoutDefaultsToOffWithoutAnyGoogleCredentialRead() {
        assertNull(MembershipRuntime.services(MapApplicationConfig()).recovery)
    }
    @Test fun testCheckoutFailsClosedUnlessAllPrerequisitesAreEnabled() {
        for(missing in listOf("recoveryEnabled","allowTestPurchases","verifiedReadEnabled")) {
            val config=MapApplicationConfig("membership.internalTestCheckoutEnabled" to "true",
                "membership.recoveryEnabled" to if(missing=="recoveryEnabled") "false" else "true",
                "membership.allowTestPurchases" to if(missing=="allowTestPurchases") "false" else "true",
                "membership.verifiedReadEnabled" to if(missing=="verifiedReadEnabled") "false" else "true")
            try {MembershipRuntime.services(config);fail("Missing $missing must block checkout")}
            catch(_: IllegalStateException) { }
        }
    }
}
