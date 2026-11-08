package com.vidoora.backend.modules.membership

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.user.UsersTable
import org.jetbrains.exposed.sql.select
import java.time.Instant
import java.util.UUID

/** A current database role grants administrative benefits independently of purchase records. */
internal object AdminViewerAccess {
    suspend fun access(account: String?, now: Instant): ViewerAccess? {
        val id = account?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val enabled = DatabaseFactory.dbQuery {
            UsersTable.select { UsersTable.id eq id }.singleOrNull()?.let {
                it[UsersTable.role] == "ADMIN" && !it[UsersTable.isSuspended]
            } == true
        }
        if (!enabled) return null
        val plan = ViewerPlan("ADMIN", "Admin access", 0, validity = "ROLE", autoRenew = false,
            adFree = true, maxHeight = null, downloadsPerPeriod = Int.MAX_VALUE)
        val grant = VerifiedViewerGrant(id.toString(), "ADMIN", "admin-access", now.minusSeconds(1),
            now.plusSeconds(86400), VerifiedPaymentState.PURCHASED, true)
        return ViewerAccess(plan, grant)
    }
}

internal class RoleAwareViewerEntitlements(private val delegate: ViewerEntitlements) : ViewerEntitlements {
    override suspend fun resolve(accountId: String?): ViewerEntitlement {
        val admin = AdminViewerAccess.access(accountId, Instant.now())
        return if (admin == null) delegate.resolve(accountId) else ViewerEntitlement(
            accountId = accountId, planId = "ADMIN", adFree = true, maxHeight = null,
            expiresAt = admin.grant!!.expiresAt.toString(), validForSeconds = 30,
            downloadsPerPeriod = Int.MAX_VALUE)
    }
}
