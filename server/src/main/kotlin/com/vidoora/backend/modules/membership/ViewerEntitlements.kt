package com.vidoora.backend.modules.membership

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.util.UUID

@Serializable
data class ViewerEntitlement(val accountId: String? = null, val planId: String = "FREE",
    val adFree: Boolean = false, val maxHeight: Int? = 480, val expiresAt: String? = null,
    val validForSeconds: Long = 30, val downloadsPerPeriod: Int = 0, val purchasesAvailable: Boolean = false)

fun interface ViewerEntitlements { suspend fun resolve(accountId: String?): ViewerEntitlement }

object FreeViewerEntitlements : ViewerEntitlements {
    override suspend fun resolve(accountId: String?) = ViewerEntitlement(accountId = accountId)
}

/** Sources must obtain grants through the server verifier. A database receipt row is NOT a grant. */
internal fun interface VerifiedGrantSource { suspend fun current(account: UUID): List<VerifiedViewerGrant> }

/** Bounded, account-scoped cache. Expiry and clock rollback invalidate it even within the refresh window. */
internal class VerifiedViewerEntitlements(private val source: VerifiedGrantSource,
    private val clock: Clock = Clock.systemUTC()) : ViewerEntitlements {
    private data class Entry(val checkedAt: Instant, val until: Instant, val value: ViewerEntitlement)
    private val locks = List(32) { Mutex() }
    private val cache = object : LinkedHashMap<UUID, Entry>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Entry>?) = size > 256
    }
    override suspend fun resolve(accountId: String?): ViewerEntitlement {
        if (accountId == null) return ViewerEntitlement()
        val account = try { UUID.fromString(accountId) } catch (_: Exception) { return ViewerEntitlement() }
        return locks[(account.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val now = clock.instant()
            val cached = synchronized(cache) { cache[account] }
            if (cached != null && now >= cached.checkedAt && now < cached.until) {
                return@withLock cached.value.copy(validForSeconds = Duration.between(now, cached.until).seconds.coerceAtLeast(0))
            }
            val grants = try { source.current(account) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            val checked = clock.instant()
            val access = grants.map { ViewerAccessPolicy.resolve(account.toString(), it, checked) }
                .filter { it.grant != null }
                .maxWithOrNull(compareBy<ViewerAccess> { it.plan.downloadsPerPeriod }.thenBy { it.grant!!.expiresAt })
            val grant = access?.grant
            val until = grant?.expiresAt?.let { minOf(it, checked.plusSeconds(60)) } ?: checked.plusSeconds(5)
            val value = if (grant == null) ViewerEntitlement(accountId = account.toString(), validForSeconds = 5)
                else ViewerEntitlement(account.toString(), access.plan.id, access.adFree, access.plan.maxHeight,
                    grant.expiresAt.toString(), Duration.between(checked, until).seconds, access.plan.downloadsPerPeriod)
            synchronized(cache) { cache[account] = Entry(checked, until, value) }
            value
        }
    }
    fun invalidate(account: UUID) { synchronized(cache) { cache.remove(account) } }
}
