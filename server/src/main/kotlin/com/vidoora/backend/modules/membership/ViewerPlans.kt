package com.vidoora.backend.modules.membership

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

@Serializable
data class ViewerPlan(val id: String,val name: String,val pricePaise: Int,val currency: String="INR",
    val validity: String,val autoRenew: Boolean,val adFree: Boolean,val maxHeight: Int?,val downloadsPerPeriod: Int)

/** Product specification only. These values never authorize access or initiate a charge. */
object ViewerPlans {
    val all: List<ViewerPlan> = listOf(
        ViewerPlan("FREE","Free",0,validity="UNLIMITED",autoRenew=false,adFree=false,maxHeight=480,downloadsPerPeriod=0),
        ViewerPlan("DAY","Day Pass",1300,validity="PT24H",autoRenew=false,adFree=true,maxHeight=null,downloadsPerPeriod=0),
        ViewerPlan("WEEK","Week Pass",3900,validity="P7D",autoRenew=false,adFree=true,maxHeight=null,downloadsPerPeriod=5),
        ViewerPlan("MONTHLY","Monthly Ad-Free",9900,validity="P1M",autoRenew=true,adFree=true,maxHeight=null,downloadsPerPeriod=30)
    )
    fun get(id: String): ViewerPlan? = all.firstOrNull { it.id==id }
}

enum class VerifiedPaymentState { PENDING, PURCHASED, REFUNDED, REVOKED }

/** Only a future server-side store verifier/repository may create these; never deserialize client claims into grants. */
internal data class VerifiedViewerGrant(val accountId: String,val planId: String,val periodId: String,
    val startsAt: Instant,val expiresAt: Instant,val state: VerifiedPaymentState,val acknowledged: Boolean)

internal data class ViewerAccess(val plan: ViewerPlan,val grant: VerifiedViewerGrant?) {
    val adFree: Boolean get()=grant!=null && plan.adFree
    fun canStream(height: Int,sourceMaximumHeight: Int): Boolean = height>0 && height<=sourceMaximumHeight && (plan.maxHeight==null || height<=plan.maxHeight)
}

internal object ViewerAccessPolicy {
    fun resolve(accountId: String?,grant: VerifiedViewerGrant?,now: Instant): ViewerAccess {
        val plan=grant?.let { ViewerPlans.get(it.planId) }
        val allowed=accountId!=null && grant!=null && grant.accountId==accountId && grant.periodId.isNotBlank() &&
            plan!=null && plan.id!="FREE" && grant.state==VerifiedPaymentState.PURCHASED && grant.acknowledged &&
            !now.isBefore(grant.startsAt) && now.isBefore(grant.expiresAt)
        return if(allowed) ViewerAccess(checkNotNull(plan),grant) else ViewerAccess(checkNotNull(ViewerPlans.get("FREE")),null)
    }
}

internal enum class DownloadDecision { ALLOW_NEW, RESUME_RESERVED, REUSE_COMPLETED, PLAN_REQUIRED, QUOTA_REACHED }

/** The eventual repository must evaluate this while holding a per-account/period database lock. */
internal object ViewerDownloadPolicy {
    fun decide(access: ViewerAccess,videoId: String,completed: Set<String>,reserved: Set<String>): DownloadDecision {
        require(videoId.isNotBlank())
        val limit=access.plan.downloadsPerPeriod
        if(access.grant==null || limit==0) return DownloadDecision.PLAN_REQUIRED
        if(videoId in completed) return DownloadDecision.REUSE_COMPLETED
        if(videoId in reserved) return DownloadDecision.RESUME_RESERVED
        return if((completed+reserved).size>=limit) DownloadDecision.QUOTA_REACHED else DownloadDecision.ALLOW_NEW
    }
    fun offlineUntil(access: ViewerAccess,now: Instant): Instant? {
        val grant=access.grant ?: return null
        if(access.plan.downloadsPerPeriod==0 || !now.isBefore(grant.expiresAt)) return null
        return minOf(grant.expiresAt,now.plus(Duration.ofHours(24)))
    }
    /** An encrypted local lease will be anchored to elapsed time. Reboot or clock rollback requires revalidation. */
    fun validOfflineLease(grantedBoot: Long,currentBoot: Long,grantedElapsedMs: Long,currentElapsedMs: Long,validForMs: Long): Boolean {
        if(grantedBoot<0 || currentBoot<0 || grantedBoot!=currentBoot || grantedElapsedMs<0 || currentElapsedMs<grantedElapsedMs || validForMs !in 1..86_400_000L) return false
        return currentElapsedMs-grantedElapsedMs<validForMs
    }
}
