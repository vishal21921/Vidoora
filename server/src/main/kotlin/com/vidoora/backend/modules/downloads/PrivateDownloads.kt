package com.vidoora.backend.modules.downloads

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.membership.*
import com.vidoora.backend.modules.playback.*
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.video.VideosTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable data class DownloadManifest(val id: String,val videoId: String,val title: String,val durationSeconds: Int,
    val version: String,val entry: String,val paths: List<String>)
@Serializable data class DownloadLease(val validForSeconds: Long,val expiresAt: String)
@Serializable data class DownloadProof(val hashes: Map<String,String>)
class DownloadDenied : RuntimeException()
class DownloadQuotaReached : RuntimeException()
class DownloadIncomplete : RuntimeException()
internal object PrivateDownloadRows : Table("private_downloads") {
    val id=uuid("id");val user=uuid("user_id").references(UsersTable.id);val period=varchar("period_hash",64)
    val video=uuid("video_id").references(VideosTable.id);val manifest=text("manifest");val charged=bool("charged").default(false)
    val state=varchar("state",16);val until=timestamp("reserved_until");val created=timestamp("created_at")
    override val primaryKey=PrimaryKey(id)
    init {uniqueIndex(user,period,video)}
}
internal object PrivateDownloadAssets : Table("private_download_assets") {
    val download=uuid("download_id").references(PrivateDownloadRows.id);val path=varchar("path",80);val hash=varchar("sha256",64)
    override val primaryKey=PrimaryKey(download,path)
}
internal object PrivateDownloadClients : Table("private_download_clients") {
    val download=uuid("download_id").references(PrivateDownloadRows.id);val client=uuid("client_id");val state=varchar("state",16);val until=timestamp("reserved_until")
    override val primaryKey=PrimaryKey(download,client)
}
private const val DEFAULT_CLIENT="00000000-0000-0000-0000-000000000000"
internal fun digest(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}

/** Fresh provider grants are injected by trusted runtime wiring, never deserialized from requests. */
class PrivateDownloads internal constructor(private val playback: PlaybackService,private val grants: VerifiedGrantSource,
    private val clock: Clock=Clock.systemUTC(),private val benefits: ViewerEntitlements=FreeViewerEntitlements) {
    private suspend fun access(account: String): ViewerAccess {
        AdminViewerAccess.access(account, clock.instant())?.let { return it }
        val verified=grants.current(UUID.fromString(account))
        val now=clock.instant()
        val candidates=verified.map{ViewerAccessPolicy.resolve(account,it,now)}
        return candidates.filter{it.grant!=null && it.plan.downloadsPerPeriod>0}
            .maxWithOrNull(compareBy<ViewerAccess>{it.plan.downloadsPerPeriod}.thenBy{it.grant!!.expiresAt}) ?: throw DownloadDenied()
    }
    private suspend fun check(account: String,manifest: DownloadManifest,token: String?) {
        if(playback.contentVersion(account,manifest.videoId,token)!=manifest.version) throw PlaybackDenied()
    }
    private fun lock(account: UUID) {
        val u=UsersTable.select{UsersTable.id eq account}.forUpdate().singleOrNull() ?: throw DownloadDenied()
        if(u[UsersTable.isSuspended]) throw DownloadDenied()
    }
    private fun checkAdminRole(account: UUID, access: ViewerAccess) {
        if (access.plan.id == "ADMIN" && UsersTable.select { UsersTable.id eq account }.single()[UsersTable.role] != "ADMIN") {
            throw DownloadDenied()
        }
    }
    private fun owned(account: String,id: String)=PrivateDownloadRows.select{
        (PrivateDownloadRows.id eq UUID.fromString(id)) and (PrivateDownloadRows.user eq UUID.fromString(account))
    }.singleOrNull() ?: throw DownloadDenied()
    private fun parsed(row: ResultRow)=Json.decodeFromString<DownloadManifest>(row[PrivateDownloadRows.manifest])
    private fun clientRow(id: String,client: String): ResultRow=PrivateDownloadClients.select{
        (PrivateDownloadClients.download eq UUID.fromString(id)) and (PrivateDownloadClients.client eq UUID.fromString(client))
    }.singleOrNull() ?: throw DownloadDenied()
    suspend fun quota(account: String): DownloadQuota {
        val access=try {access(account)} catch (_: DownloadDenied) {return DownloadQuota(0,0,0,0)}
        val grant=checkNotNull(access.grant)
        val period=digest(grant.periodId.toByteArray(Charsets.UTF_8))
        return DatabaseFactory.dbQuery {
            lock(UUID.fromString(account))
            checkAdminRole(UUID.fromString(account), access)
            val now=clock.instant()
            if(now>=grant.expiresAt) return@dbQuery DownloadQuota(0,0,0,0)
            val rows=PrivateDownloadRows.select {(PrivateDownloadRows.user eq UUID.fromString(account)) and (PrivateDownloadRows.period eq period)}.toList()
            val used=rows.count {it[PrivateDownloadRows.charged]}
            val reserved=rows.count {!it[PrivateDownloadRows.charged] && it[PrivateDownloadRows.state]=="RESERVED" && it[PrivateDownloadRows.until]>now}
            DownloadQuota(access.plan.downloadsPerPeriod,used,reserved,
                if (access.plan.id == "ADMIN") Int.MAX_VALUE else (access.plan.downloadsPerPeriod-used-reserved).coerceAtLeast(0))
        }
    }
    suspend fun reserve(account: String,videoId: String,token: String?,client: String=DEFAULT_CLIENT): DownloadManifest {
        val details=playback.details(account,videoId,token)
        if(!details.canPlay) throw PlaybackUnavailable()
        val access=access(account);val grant=checkNotNull(access.grant)
        val version=playback.contentVersion(account,videoId,token)
        val master=playback.asset(account,videoId,"master.m3u8",token).toString(Charsets.UTF_8)
        val variants=master.lineSequence().filter{Regex("[0-9]{1,3}p/index\\.m3u8").matches(it)}.toList()
        val entry=variants.filter{it.substringBefore('p').toInt()<=720}.maxByOrNull{it.substringBefore('p').toInt()}
            ?: throw PlaybackUnavailable()
        val index=playback.asset(account,videoId,entry,token).toString(Charsets.UTF_8)
        val segments=index.lineSequence().filter{it.isNotBlank() && !it.startsWith("#")}.map{entry.substringBefore('/')+"/"+it}.toList()
        if(segments.isEmpty() || segments.size>10000 || segments.distinct().size!=segments.size) throw PlaybackUnavailable()
        val candidate=DownloadManifest(UUID.randomUUID().toString(),videoId,details.title,details.durationSeconds,version,entry,listOf(entry)+segments)
        check(account,candidate,token)
        val now=clock.instant();val period=digest(grant.periodId.toByteArray(Charsets.UTF_8));val owner=UUID.fromString(account)
        return DatabaseFactory.dbQuery {
            lock(owner)
            checkAdminRole(owner, access)
            if(clock.instant()>=grant.expiresAt) throw DownloadDenied()
            val rows=PrivateDownloadRows.select{(PrivateDownloadRows.user eq owner) and (PrivateDownloadRows.period eq period)}.toList()
            val existing=rows.singleOrNull{it[PrivateDownloadRows.video]==UUID.fromString(videoId)}
            val holds=existing?.let{it[PrivateDownloadRows.charged] || (it[PrivateDownloadRows.state]=="RESERVED" && it[PrivateDownloadRows.until]>now)}==true
            if(access.plan.id != "ADMIN" && !holds && rows.count{it[PrivateDownloadRows.charged] || (it[PrivateDownloadRows.state]=="RESERVED" && it[PrivateDownloadRows.until]>now)}>=access.plan.downloadsPerPeriod) throw DownloadQuotaReached()
            val result=if(existing==null) candidate else candidate.copy(id=existing[PrivateDownloadRows.id].toString())
            val deadline=minOf(grant.expiresAt,now.plusSeconds(1800))
            if(existing==null) PrivateDownloadRows.insert {
                it[id]=UUID.fromString(result.id);it[user]=owner;it[PrivateDownloadRows.period]=period;it[video]=UUID.fromString(videoId)
                it[manifest]=Json.encodeToString(result);it[state]="RESERVED";it[until]=deadline;it[created]=now
            } else {
                if(parsed(existing).version!=version) {
                    PrivateDownloadAssets.deleteWhere{download eq existing[PrivateDownloadRows.id]}
                    PrivateDownloadClients.update({PrivateDownloadClients.download eq existing[PrivateDownloadRows.id]}) {it[state]="CANCELLED"}
                }
                PrivateDownloadRows.update({PrivateDownloadRows.id eq existing[PrivateDownloadRows.id]}) {
                    it[manifest]=Json.encodeToString(result);it[state]="RESERVED";it[until]=deadline
                }
            }
            PrivateDownloadClients.deleteWhere{(download eq UUID.fromString(result.id)) and (PrivateDownloadClients.client eq UUID.fromString(client))}
            PrivateDownloadClients.insert{it[download]=UUID.fromString(result.id);it[PrivateDownloadClients.client]=UUID.fromString(client);it[state]="ACTIVE";it[until]=deadline}
            result
        }
    }
    suspend fun expectedVersion(account: String,id: String): String=DatabaseFactory.dbQuery{parsed(owned(account,id)).version}
    suspend fun asset(account: String,id: String,path: String,token: String?,client: String=DEFAULT_CLIENT): ByteArray {
        val manifest=DatabaseFactory.dbQuery {
            val row=owned(account,id)
            val session=clientRow(id,client)
            if(session[PrivateDownloadClients.state]!="ACTIVE" || session[PrivateDownloadClients.until]<=clock.instant()) throw DownloadDenied()
            parsed(row)
        }
        if(path !in manifest.paths || !PlaybackPaths.validAsset(path)) throw DownloadDenied()
        check(account,manifest,token)
        val current=benefits.resolve(account)
        if(current.accountId!=account || current.downloadsPerPeriod<=0 || current.expiresAt==null || Instant.parse(current.expiresAt)<=clock.instant()) throw DownloadDenied()
        val bytes=playback.asset(account,manifest.videoId,path,token)
        check(account,manifest,token)
        return bytes
    }
    suspend fun delivered(account: String,id: String,path: String,hash: String,client: String=DEFAULT_CLIENT,expectedVersion: String?=null) = DatabaseFactory.dbQuery {
        lock(UUID.fromString(account));val row=owned(account,id)
        val session=clientRow(id,client)
        if(expectedVersion!=null && parsed(row).version!=expectedVersion) return@dbQuery
        if(session[PrivateDownloadClients.state]!="ACTIVE" || session[PrivateDownloadClients.until]<=clock.instant() || path !in parsed(row).paths) return@dbQuery
        PrivateDownloadAssets.deleteWhere{(download eq UUID.fromString(id)) and (PrivateDownloadAssets.path eq path)}
        PrivateDownloadAssets.insert{it[download]=UUID.fromString(id);it[PrivateDownloadAssets.path]=path;it[PrivateDownloadAssets.hash]=hash}
        val deadline=clock.instant().plusSeconds(1800)
        PrivateDownloadClients.update({(PrivateDownloadClients.download eq UUID.fromString(id)) and (PrivateDownloadClients.client eq UUID.fromString(client))}) {it[until]=deadline}
        PrivateDownloadRows.update({PrivateDownloadRows.id eq UUID.fromString(id)}) {it[until]=maxOf(row[PrivateDownloadRows.until],deadline)}
    }
    suspend fun complete(account: String,id: String,token: String?,proof: DownloadProof,client: String=DEFAULT_CLIENT): DownloadLease {
        val manifest=DatabaseFactory.dbQuery{parsed(owned(account,id))}
        check(account,manifest,token)
        val access=access(account);check(account,manifest,token)
        val grant=checkNotNull(access.grant);val now=clock.instant()
        val period=digest(grant.periodId.toByteArray(Charsets.UTF_8))
        val planUntil=ViewerDownloadPolicy.offlineUntil(access,now) ?: throw DownloadDenied()
        val until=minOf(planUntil,playback.offlineDeadline(account,manifest.videoId,token) ?: planUntil)
        if(java.time.Duration.between(now,until).seconds<=0) throw DownloadDenied()
        DatabaseFactory.dbQuery {
            lock(UUID.fromString(account));val row=owned(account,id)
            checkAdminRole(UUID.fromString(account), access)
            if(clock.instant()>=grant.expiresAt) throw DownloadDenied()
            val session=clientRow(id,client)
            if(row[PrivateDownloadRows.period]!=period || session[PrivateDownloadClients.state]=="CANCELLED" ||
                (!row[PrivateDownloadRows.charged] && session[PrivateDownloadClients.until]<=now)) throw DownloadDenied()
            val delivered=PrivateDownloadAssets.select{PrivateDownloadAssets.download eq UUID.fromString(id)}.associate{it[PrivateDownloadAssets.path] to it[PrivateDownloadAssets.hash]}
            if(proof.hashes.keys!=manifest.paths.toSet() || proof.hashes!=delivered || proof.hashes.values.any{!Regex("[0-9a-f]{64}").matches(it)}) throw DownloadIncomplete()
            PrivateDownloadRows.update({PrivateDownloadRows.id eq UUID.fromString(id)}) {it[charged]=true;it[state]="COMPLETED"}
            PrivateDownloadClients.update({(PrivateDownloadClients.download eq UUID.fromString(id)) and (PrivateDownloadClients.client eq UUID.fromString(client))}) {it[state]="COMPLETE"}
        }
        val duration=java.time.Duration.between(clock.instant(),until).seconds
        if(duration<=0) throw DownloadDenied()
        return DownloadLease(duration,until.toString())
    }
    suspend fun cancel(account: String,id: String,client: String=DEFAULT_CLIENT)=DatabaseFactory.dbQuery {
        lock(UUID.fromString(account));val row=owned(account,id);clientRow(id,client)
        PrivateDownloadClients.update({(PrivateDownloadClients.download eq UUID.fromString(id)) and (PrivateDownloadClients.client eq UUID.fromString(client))}) {it[state]="CANCELLED"}
        val remaining=PrivateDownloadClients.select{(PrivateDownloadClients.download eq UUID.fromString(id)) and (PrivateDownloadClients.state eq "ACTIVE")}
            .map{it[PrivateDownloadClients.until]}
        val deadline=remaining.maxOrNull() ?: clock.instant()
        PrivateDownloadRows.update({PrivateDownloadRows.id eq UUID.fromString(id)}) {
            it[until]=deadline;it[state]=if(row[PrivateDownloadRows.charged]) "COMPLETED" else if(remaining.isEmpty()) "CANCELLED" else "RESERVED"
        }
        // charged is retained, and another device's active session keeps its own reservation.
    }
}

@Serializable data class DownloadQuota(val limit: Int,val used: Int,val reserved: Int,val remaining: Int)
