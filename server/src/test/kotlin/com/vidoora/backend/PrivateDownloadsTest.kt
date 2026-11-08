package com.vidoora.backend

import com.vidoora.backend.infrastructure.*
import com.vidoora.backend.modules.auth.*
import com.vidoora.backend.modules.downloads.*
import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.video.VideosTable
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.*
import java.util.UUID

class PrivateDownloadsTest {
    private val config=MapApplicationConfig("jwt.secret" to "fixture-only-secret-at-least-32-characters-long",
        "database.jdbcUrl" to "jdbc:h2:mem:vidoora_tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL","database.driverClassName" to "org.h2.Driver",
        "database.username" to "sa","database.password" to "","database.allowInMemoryFallback" to "true","r2.mockMode" to "true")
    private class MutableClock(var value: Instant): Clock(){override fun instant()=value;override fun getZone()=ZoneOffset.UTC;override fun withZone(z: ZoneId)=this}
    private val clock=MutableClock(Instant.now());private lateinit var owner: String;private lateinit var other: String
    private lateinit var playback: PlaybackService;private lateinit var service: PrivateDownloads
    private var plan="WEEK";private var period="fixture-period";private var expiry=clock.instant().plusSeconds(604800)
    private var duringGrant: (suspend ()->Unit)?=null
    private class Storage(config: MapApplicationConfig): S3StorageService(config) {
        var reads=0
        override fun readProcessed(bucket: String,key: String,maxBytes: Int): ByteArray {
            reads++
            return when {
                key.endsWith("master.m3u8")->"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n720p/index.m3u8\n".toByteArray()
                key.endsWith("index.m3u8")->"#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nsegment_000000.ts\n#EXT-X-ENDLIST\n".toByteArray()
                else->byteArrayOf(1,2,3,4)
            }
        }
    }
    private lateinit var storage: Storage
    @Before fun setup()=runBlocking {
        DatabaseFactory.init(config);val auth=AuthService(JwtConfig(config))
        suspend fun register(): String{val id=UUID.randomUUID().toString().take(8);return auth.register(RegisterRequest("d_$id","$id@example.test","Password123!","Fixture")).user.id}
        owner=register();other=register();storage=Storage(config)
        val benefits=ViewerEntitlements{ViewerEntitlement(it,plan,plan!="FREE",if(plan=="FREE")480 else null,expiry.toString(),60,ViewerPlans.get(plan)?.downloadsPerPeriod ?: 0)}
        playback=PlaybackService(storage,shareTokens=VideoShareTokens(JwtConfig(config)),entitlements=benefits)
        service=PrivateDownloads(playback,VerifiedGrantSource {account->
            duringGrant?.invoke()
            if(plan=="FREE") emptyList() else listOf(VerifiedViewerGrant(account.toString(),plan,period,clock.instant().minusSeconds(1),expiry,VerifiedPaymentState.PURCHASED,true))
        },clock,benefits)
    }
    private suspend fun video(): String {
        val id=UUID.randomUUID()
        DatabaseFactory.dbQuery{VideosTable.insert {
            it[VideosTable.id]=id;it[uploaderId]=UUID.fromString(owner);it[title]="Download fixture";it[publicationState]="PROCESSED";it[durationSeconds]=6
            it[processedBucket]="vidoora-test-download";it[masterPlaylistKey]="processed/$id/${UUID.randomUUID()}/master.m3u8"
        }}
        return id.toString()
    }
    private suspend fun transfer(manifest: DownloadManifest): DownloadProof {
        val hashes=manifest.paths.associateWith{path->val bytes=service.asset(owner,manifest.id,path,null);val hash=digest(bytes);service.delivered(owner,manifest.id,path,hash);hash}
        return DownloadProof(hashes)
    }
    @Test fun adminCanReserveBeyondPaidLimitsAndLosesAccessWhenRoleChanges()=runBlocking {
        plan="FREE"
        DatabaseFactory.dbQuery {
            com.vidoora.backend.modules.user.UsersTable.update({com.vidoora.backend.modules.user.UsersTable.id eq UUID.fromString(owner)}) {
                it[role]="ADMIN"
            }
        }
        val benefits=RoleAwareViewerEntitlements(FreeViewerEntitlements)
        playback=PlaybackService(storage,shareTokens=VideoShareTokens(JwtConfig(config)),entitlements=benefits)
        service=PrivateDownloads(playback,VerifiedGrantSource { emptyList() },clock,benefits)
        val manifests=(1..31).map { service.reserve(owner,video(),null) }
        assertEquals(Int.MAX_VALUE,service.quota(owner).remaining)
        val first=manifests.first()
        assertEquals(86400,service.complete(owner,first.id,null,transfer(first)).validForSeconds)
        DatabaseFactory.dbQuery {
            com.vidoora.backend.modules.user.UsersTable.update({com.vidoora.backend.modules.user.UsersTable.id eq UUID.fromString(owner)}) {
                it[role]="USER"
            }
        }
        assertEquals(0,service.quota(owner).limit)
        try { service.reserve(owner,video(),null); fail() } catch (_: DownloadDenied) {}
    }
    @Test fun remainingCounterIncludesActiveReservationsAndResetsWithPeriod()=runBlocking {
        assertEquals(5,service.quota(owner).remaining)
        val manifest=service.reserve(owner,video(),null)
        assertEquals(1,service.quota(owner).reserved)
        assertEquals(4,service.quota(owner).remaining)
        service.complete(owner,manifest.id,null,transfer(manifest))
        assertEquals(1,service.quota(owner).used)
        assertEquals(0,service.quota(owner).reserved)
        service.cancel(owner,manifest.id)
        assertEquals(4,service.quota(owner).remaining) // Removing a saved download never refunds a charged slot.
        period="next-period"
        assertEquals(5,service.quota(owner).remaining)
        plan="FREE"
        assertEquals(0,service.quota(owner).remaining)
    }
    @Test fun freeDayAndPrivateIdOnlyViewersCannotReserve()=runBlocking {
        val id=video();plan="FREE"
        try{service.reserve(owner,id,null);fail()}catch(_: DownloadDenied){}
        plan="DAY";try{service.reserve(owner,id,null);fail()}catch(_: DownloadDenied){}
        plan="WEEK";try{service.reserve(other,id,null);fail()}catch(_: PlaybackDenied){}
        assertEquals(0,storage.reads)
    }
    @Test fun quotaIsAtomicAcrossParallelDevicesAndCancelledUnusedSlotsAreReleased()=runBlocking {
        val ids=(1..6).map{video()}
        val results=ids.map{id->async(Dispatchers.IO){runCatching{service.reserve(owner,id,null)}}}.awaitAll()
        assertEquals(5,results.count{it.isSuccess});assertEquals(1,results.count{it.exceptionOrNull() is DownloadQuotaReached})
        val first=results.first{it.isSuccess}.getOrThrow();service.cancel(owner,first.id)
        val deniedIndex=results.indexOfFirst{it.isFailure};assertNotNull(service.reserve(owner,ids[deniedIndex],null))
    }
    @Test fun completionRequiresEveryServerObservedAssetAndMatchingProof()=runBlocking {
        val m=service.reserve(owner,video(),null)
        try{service.complete(owner,m.id,null,DownloadProof(emptyMap()));fail()}catch(_: DownloadIncomplete){}
        val proof=transfer(m)
        try{service.complete(owner,m.id,null,DownloadProof(proof.hashes.mapValues{"0".repeat(64)}));fail()}catch(_: DownloadIncomplete){}
        val lease=service.complete(owner,m.id,null,proof);assertEquals(86400,lease.validForSeconds)
        assertEquals(lease,service.complete(owner,m.id,null,proof))
        val again=service.reserve(owner,m.videoId,null);assertEquals(m.id,again.id)
        service.cancel(owner,m.id)
        repeat(4){service.reserve(owner,video(),null)}
        try{service.reserve(owner,video(),null);fail()}catch(_: DownloadQuotaReached){} // Removing a completed copy does not refund its allowance.
    }
    @Test fun reservationExpiryReleasesUnusedSlotsAndCompletionCannotUseAnOldPeriod()=runBlocking {
        val originals=(1..5).map{service.reserve(owner,video(),null)}
        clock.value=clock.value.plusSeconds(1801)
        val fresh=service.reserve(owner,video(),null);val proof=transfer(fresh)
        period="new-fixture-period"
        try{service.complete(owner,fresh.id,null,proof);fail()}catch(_: DownloadDenied){}
        val renewed=service.reserve(owner,fresh.videoId,null);assertNotEquals(fresh.id,renewed.id)
        try{service.asset(other,originals.first().id,originals.first().entry,null);fail()}catch(_: DownloadDenied){}
    }
    @Test fun cancellingOneDeviceDoesNotCancelAnotherDevicesTransfer()=runBlocking {
        val video=video();val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        val first=service.reserve(owner,video,null,a);val second=service.reserve(owner,video,null,b)
        assertEquals(first.id,second.id);service.cancel(owner,first.id,a)
        try{service.asset(owner,first.id,first.entry,null,a);fail()}catch(_: DownloadDenied){}
        assertTrue(service.asset(owner,second.id,second.entry,null,b).isNotEmpty())
        assertEquals(second.id,service.reserve(owner,video,null,b).id)
    }
    @Test fun sharedDownloadLeaseCannotOutliveItsCapability()=runBlocking {
        val id=video();playback.publish(owner,id,"UNLISTED")
        val version=DatabaseFactory.dbQuery{VideosTable.select{VideosTable.id eq UUID.fromString(id)}.single()[VideosTable.shareVersion]}
        val token=VideoShareTokens(JwtConfig(config)).issue(UUID.fromString(id),version,Instant.now().plusSeconds(45))
        val manifest=service.reserve(other,id,token)
        val proof=DownloadProof(manifest.paths.associateWith{path->val bytes=service.asset(other,manifest.id,path,token);val hash=digest(bytes);service.delivered(other,manifest.id,path,hash);hash})
        assertTrue(service.complete(other,manifest.id,token,proof).validForSeconds in 1..45)
    }
    @Test fun lossOfDownloadBenefitsDeniesFurtherAssetsAndLeaseValidation()=runBlocking {
        val manifest=service.reserve(owner,video(),null);val reads=storage.reads
        plan="FREE"
        try{service.asset(owner,manifest.id,manifest.entry,null);fail()}catch(_: DownloadDenied){}
        assertEquals(reads,storage.reads)
        try{service.complete(owner,manifest.id,null,DownloadProof(emptyMap()));fail()}catch(_: DownloadDenied){}
    }
    @Test fun monthlyQuotaIsThirtyAndNewPeriodsHaveTheirOwnAllowance()=runBlocking {
        plan="MONTHLY"
        repeat(30){service.reserve(owner,video(),null)}
        val next=video();try{service.reserve(owner,next,null);fail()}catch(_: DownloadQuotaReached){}
        period="fixture-next-month";assertNotNull(service.reserve(owner,next,null))
    }
    @Test fun leaseEndsAtPlanExpiryAndShareRevocationWinsDuringVerification()=runBlocking {
        expiry=clock.instant().plusSeconds(50)
        val m=service.reserve(owner,video(),null);val proof=transfer(m)
        assertEquals(50,service.complete(owner,m.id,null,proof).validForSeconds)
        val id=video();playback.publish(owner,id,"UNLISTED")
        val token=playback.shareLink(owner,id).url.substringAfter('#')
        duringGrant={playback.unpublish(owner,id)}
        try{service.reserve(other,id,token);fail()}catch(_: PlaybackDenied){}
    }
}
