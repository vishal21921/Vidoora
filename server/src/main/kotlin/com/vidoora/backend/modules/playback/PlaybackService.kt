package com.vidoora.backend.modules.playback

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.video.VideosTable
import com.vidoora.backend.modules.video.UploadSessionsTable
import com.vidoora.backend.modules.video.ProcessingJobsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq


import java.time.Instant

import java.util.UUID

@Serializable data class OwnerVideoStatusRequest(val ids: List<String>,val search: String="",val sort: String="NEWEST",val state: String="ALL",val sharing: String="ALL",val folder: String="ALL")
@Serializable data class LibraryVideo(val id: String,val title: String,val publicationState: String,
    val durationSeconds: Int,val visibility: String,val published: Boolean=false,val thumbnailAvailable: Boolean=false,val folderId: String?=null,val thumbnailVersion: String?=null,val processingPercent: Int?=null)
@Serializable data class VideoDetails(val id: String,val title: String,val description: String?,val publicationState: String,
    val durationSeconds: Int,val visibility: String,val published: Boolean,val isOwner: Boolean,val canPlay: Boolean,val creatorName: String,val folderId: String?=null,val thumbnailVersion: String?=null,val processingPercent: Int?=null)
@Serializable data class FeedVideo(val id: String,val title: String,val durationSeconds: Int,val creatorName: String,val folderId: String?=null,val thumbnailVersion: String?=null)
@Serializable data class DiscoveryPage(val items: List<FeedVideo>,val nextCursor: String?)
@Serializable data class PublishRequest(val visibility: String)
@Serializable data class VideoShareLink(val url: String,val expiresAt: String?=null)
@Serializable data class SafetyResult(val accepted: Boolean=true)
@Serializable data class BlockedUploader(val id: String,val displayName: String)
@Serializable data class BlockedUploadersPage(val items: List<BlockedUploader>,val nextCursor: String?)
class PlaybackDenied : RuntimeException()
class PlaybackUnavailable : RuntimeException()
class DeletionBusy : RuntimeException()

object PlaybackPaths {
    private val assetPattern = Regex("(?:master\\.m3u8|[0-9]{1,3}p/(?:index\\.m3u8|segment_[0-9]{6}\\.ts))")
    fun validAsset(path: String): Boolean = assetPattern.matches(path)
    fun validateManifest(path: String,text: String) {
        check(text.startsWith("#EXTM3U") && !Regex("URI\\s*=",RegexOption.IGNORE_CASE).containsMatchIn(text)) { "Unsupported playlist" }
        val references=text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        check(references.isNotEmpty()) { "Empty playlist" }
        val pattern=if(path=="master.m3u8") Regex("[0-9]{1,3}p/index\\.m3u8") else Regex("segment_[0-9]{6}\\.ts")
        check(references.all { pattern.matches(it) }) { "Unsupported playlist reference" }
        if(path!="master.m3u8") check(text.contains("#EXT-X-ENDLIST")) { "Not a VOD playlist" }
    }
}

class PlaybackService(private val storage: S3StorageService,private val database: Database?=null,
    private val shareTokens: VideoShareTokens?=null,
    private val entitlements: com.vidoora.backend.modules.membership.ViewerEntitlements=com.vidoora.backend.modules.membership.FreeViewerEntitlements) {
    private val reads=Semaphore(4)
    private suspend fun <T> query(block: ()->T): T = if(database==null) DatabaseFactory.dbQuery { block() }
        else org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction(Dispatchers.IO,db=database) { block() }
    private fun user(id: UUID): ResultRow {
        val row=UsersTable.select { UsersTable.id eq id }.singleOrNull() ?: throw PlaybackDenied()
        if(row[UsersTable.isSuspended]) throw PlaybackDenied()
        return row
    }
    private fun id(value: String): UUID = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw PlaybackDenied() }
    private fun allowed(viewer: UUID?,video: ResultRow,token: String?): Boolean {
        if(video[VideosTable.deletedAt]!=null) return false
        user(video[VideosTable.uploaderId])
        if(video[VideosTable.uploaderId]==viewer) return true
        if(viewer!=null && UserBlocksTable.select { (UserBlocksTable.blockerId eq viewer) and (UserBlocksTable.blockedId eq video[VideosTable.uploaderId]) }.any()) return false
        if(!video[VideosTable.isPublished] || video[VideosTable.publishedAt]==null || video[VideosTable.publicationState]!="PROCESSED") return false
        return when(video[VideosTable.visibility]) {
            "PUBLIC" -> false // Defense in depth even before legacy metadata is migrated.
            "UNLISTED" -> shareTokens?.valid(token,video[VideosTable.id],video[VideosTable.shareVersion])==true
            else -> false
        }
    }
    private fun video(viewer: UUID?,videoId: UUID,token: String?): ResultRow {
        viewer?.let { user(it) }
        val row=VideosTable.select { VideosTable.id eq videoId }.singleOrNull() ?: throw PlaybackDenied()
        if(!allowed(viewer,row,token)) throw PlaybackDenied()
        return row
    }
    private fun owned(owner: UUID,videoId: UUID): ResultRow {
        user(owner)
        val row=VideosTable.select { VideosTable.id eq videoId }.forUpdate().singleOrNull() ?: throw PlaybackDenied()
        if(row[VideosTable.uploaderId]!=owner || row[VideosTable.deletedAt]!=null) throw PlaybackDenied()
        return row
    }
    // Serialize this account's block/unblock mutations.
    private fun lockViewer(viewer: UUID) {
        user(viewer)
        UsersTable.select { UsersTable.id eq viewer }.forUpdate().single()
    }
    suspend fun blockUploader(userId: String,videoId: String,token: String?): SafetyResult = query {
        val viewer=id(userId); lockViewer(viewer)
        val row=VideosTable.select { VideosTable.id eq id(videoId) }.singleOrNull() ?: throw PlaybackDenied()
        if(row[VideosTable.deletedAt]!=null) throw PlaybackDenied()
        val uploader=row[VideosTable.uploaderId]
        if(uploader==viewer) throw PlaybackDenied()
        // A repeated block is harmless; new blocks still require current link access.
        val existing=UserBlocksTable.select { (UserBlocksTable.blockerId eq viewer) and (UserBlocksTable.blockedId eq uploader) }.any()
        if(!existing) {
            if(!allowed(viewer,row,token)) throw PlaybackDenied()
            UserBlocksTable.insert { it[blockerId]=viewer; it[blockedId]=uploader }
        }
        SafetyResult()
    }
    suspend fun blockedUploaders(userId: String,cursor: String?): BlockedUploadersPage = query {
        val viewer=id(userId); user(viewer)
        val after=cursor?.let { id(it) }
        val rows=UserBlocksTable.select {
            (UserBlocksTable.blockerId eq viewer) and (if(after==null) Op.TRUE else UserBlocksTable.blockedId greater after)
        }.orderBy(UserBlocksTable.blockedId).limit(51).toList()
        val items=rows.take(50).map { row ->
            val target=row[UserBlocksTable.blockedId]
            val name=UsersTable.select { UsersTable.id eq target }.single()[UsersTable.displayName]
            BlockedUploader(target.toString(),name)
        }
        BlockedUploadersPage(items,if(rows.size>50) items.last().id else null)
    }
    suspend fun unblockUploader(userId: String,uploaderId: String): SafetyResult = query {
        val viewer=id(userId); lockViewer(viewer)
        val target=id(uploaderId)
        UserBlocksTable.deleteWhere { (blockerId eq viewer) and (blockedId eq target) }
        SafetyResult()
    }
    suspend fun deleteVideo(userId: String,videoId: String): SafetyResult = query {
        val owner=id(userId); val parsed=id(videoId); user(owner)
        val initial=VideosTable.select { VideosTable.id eq parsed }.singleOrNull() ?: throw PlaybackDenied()
        if(initial[VideosTable.uploaderId]!=owner) throw PlaybackDenied()
        if(initial[VideosTable.deletedAt]!=null) return@query SafetyResult()
        // Match upload completion's session-first locking, then processing job before video.
        val sessions=UploadSessionsTable.select { UploadSessionsTable.videoId eq parsed }.forUpdate().toList()
        if(sessions.any { it[UploadSessionsTable.status]=="ACTIVE" }) throw DeletionBusy()
        ProcessingJobsTable.select { ProcessingJobsTable.videoId eq parsed }.forUpdate().toList()
        val row=VideosTable.select { VideosTable.id eq parsed }.forUpdate().single()
        if(row[VideosTable.deletedAt]!=null) return@query SafetyResult()
        ProcessingJobsTable.update({ ProcessingJobsTable.videoId eq parsed }) {
            it[status]="CANCELLED"; it[workerId]=null; it[leaseUntil]=null
        }
        val now=Instant.now()
        VideosTable.update({ VideosTable.id eq parsed }) {
            it[deletedAt]=now; it[publicationState]="DELETED"; it[isPublished]=false; it[publishedAt]=null
            it[shareVersion]=UUID.randomUUID(); it[title]="Deleted video"; it[description]=null
            it[thumbnailWorkerId]=null; it[thumbnailLeaseUntil]=null
            it[deletionCleanupNextAt]=now.plusSeconds(300); it[updatedAt]=now
        }
        SafetyResult()
    }
    private fun ownedFolder(owner: UUID,folder: UUID): ResultRow = VideoFoldersTable.select {
        (VideoFoldersTable.id eq folder) and (VideoFoldersTable.ownerId eq owner)
    }.singleOrNull() ?: throw PlaybackDenied()
    suspend fun folders(userId: String): List<VideoFolder> = query {
        val owner=id(userId); user(owner)
        VideoFoldersTable.select { VideoFoldersTable.ownerId eq owner }.orderBy(VideoFoldersTable.nameKey).limit(100).map { VideoFolder(it[VideoFoldersTable.id].toString(),it[VideoFoldersTable.name]) }
    }
    suspend fun saveFolder(userId: String,folderId: String?,request: FolderNameRequest): VideoFolder = query {
        val owner=id(userId); lockViewer(owner)
        val name=request.name.trim(); val key=name.lowercase(java.util.Locale.ROOT)
        require(name.length in 1..64 && key.length<=128 && !name.contains('\u0000')) { "Folder name must be 1–64 characters" }
        val folder=folderId?.let { id(it).also { value -> ownedFolder(owner,value) } }
        require(!VideoFoldersTable.select { (VideoFoldersTable.ownerId eq owner) and (VideoFoldersTable.nameKey eq key) }.any { it[VideoFoldersTable.id]!=folder }) { "Folder name already exists" }
        if(folder==null) {
            require(VideoFoldersTable.select { VideoFoldersTable.ownerId eq owner }.count()<100) { "Maximum 100 folders per account" }
            val created=UUID.randomUUID()
            VideoFoldersTable.insert { it[id]=created; it[ownerId]=owner; it[VideoFoldersTable.name]=name; it[nameKey]=key }
            VideoFolder(created.toString(),name)
        } else {
            VideoFoldersTable.update({ VideoFoldersTable.id eq folder }) { it[VideoFoldersTable.name]=name; it[nameKey]=key }
            VideoFolder(folder.toString(),name)
        }
    }
    suspend fun removeFolder(userId: String,folderId: String): SafetyResult = query {
        val owner=id(userId); lockViewer(owner); val folder=id(folderId); ownedFolder(owner,folder)
        VideosTable.update({ (VideosTable.uploaderId eq owner) and (VideosTable.folderId eq folder) }) { it[VideosTable.folderId]=null }
        VideoFoldersTable.deleteWhere { (VideoFoldersTable.id eq folder) and (ownerId eq owner) }
        SafetyResult()
    }
    suspend fun moveToFolder(userId: String,videoId: String,folderId: String?): VideoDetails {
        query {
            val owner=id(userId); lockViewer(owner)
            val folder=folderId?.let { id(it).also { value -> ownedFolder(owner,value) } }
            val video=id(videoId); owned(owner,video)
            VideosTable.update({ VideosTable.id eq video }) { it[VideosTable.folderId]=folder }
        }
        return details(userId,videoId)
    }
    suspend fun libraryPage(userId: String,search: String="",cursor: String?=null,limit: Int=25,
        sort: String="NEWEST",state: String="ALL",sharing: String="ALL",folder: String="ALL",onlyIds: List<UUID>?=null): LibraryPage = query {
        val owner=id(userId); user(owner)
        val term=search.trim().lowercase(java.util.Locale.ROOT)
        require(!term.contains('\u0000')) { "Invalid search text" }
        require(term.length<=100) { "Search must be at most 100 characters" }
        require(limit in 1..50) { "Page size must be between 1 and 50" }
        require(sort in setOf("NEWEST","OLDEST")) { "Invalid library sort" }
        require(state in setOf("ALL","READY","PROCESSING","FAILED")) { "Invalid library status filter" }
        require(sharing in setOf("ALL","PRIVATE","SHARED")) { "Invalid library sharing filter" }
        val folderId=if(folder in setOf("ALL","UNFILED")) null else id(folder).also { ownedFolder(owner,it) }
        val scope=LibraryViews.scope(owner,term,sort,state,sharing,folder)
        val after=cursor?.let { LibraryCursor.parse(it) }
        require(after==null || after.scope==scope || (after.scope==null && sort=="NEWEST" && state=="ALL" && sharing=="ALL" && folder=="ALL")) { "Cursor does not match this library view" }
        val direction=if(sort=="OLDEST") SortOrder.ASC else SortOrder.DESC
        // Escape LIKE syntax so user input always means a literal substring.
        val pattern="%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%"
        val rows=VideosTable.select {
            val folderMatch=when(folder) { "ALL" -> Op.TRUE; "UNFILED" -> VideosTable.folderId.isNull(); else -> VideosTable.folderId eq folderId }
            val statusMatch=when(state) {
                "READY" -> VideosTable.publicationState eq "PROCESSED"
                "PROCESSING" -> VideosTable.publicationState inList listOf("UPLOADED","PROCESSING")
                "FAILED" -> VideosTable.publicationState eq "PROCESSING_FAILED"
                else -> Op.TRUE
            }
            val sharingMatch=when(sharing) {
                "SHARED" -> (VideosTable.isPublished eq true) and (VideosTable.visibility eq "UNLISTED")
                "PRIVATE" -> (VideosTable.isPublished eq false) or (VideosTable.visibility neq "UNLISTED")
                else -> Op.TRUE
            }
            (VideosTable.uploaderId eq owner) and VideosTable.deletedAt.isNull() and folderMatch and statusMatch and sharingMatch and
                (if(onlyIds==null) Op.TRUE else VideosTable.id inList onlyIds) and
                (if(term.isEmpty()) Op.TRUE else (VideosTable.title.lowerCase().like(LikePattern(pattern,'!')) or VideosTable.description.lowerCase().like(LikePattern(pattern,'!')))) and
                (if(after==null) Op.TRUE else if(sort=="OLDEST") ((VideosTable.createdAt greater after.createdAt) or
                    ((VideosTable.createdAt eq after.createdAt) and (VideosTable.id greater after.id))) else
                    ((VideosTable.createdAt less after.createdAt) or ((VideosTable.createdAt eq after.createdAt) and (VideosTable.id less after.id))))
        }.orderBy(VideosTable.createdAt to direction,VideosTable.id to direction).limit(limit+1).toList()
        val page=rows.take(limit)
        LibraryPage(page.map { row -> LibraryVideo(row[VideosTable.id].toString(),row[VideosTable.title],row[VideosTable.publicationState],row[VideosTable.durationSeconds],row[VideosTable.visibility],row[VideosTable.isPublished],row[VideosTable.thumbnailObjectKey]!=null,row[VideosTable.folderId]?.toString(),row[VideosTable.updatedAt].toEpochMilli().toString()) },
            if(rows.size>limit) page.last().let { LibraryCursor(it[VideosTable.createdAt],it[VideosTable.id],scope).encode() } else null)
    }
    /** Bounded, owner-only snapshots use exactly the library view's authorization/search/filter rules. */
    suspend fun libraryStatuses(userId: String,request: OwnerVideoStatusRequest): List<LibraryVideo> {
        require(request.ids.size in 1..50) { "Supply between 1 and 50 video IDs" }
        val ids=request.ids.map { id(it) }.distinct()
        val items=libraryPage(userId,request.search,limit=50,sort=request.sort,state=request.state,sharing=request.sharing,folder=request.folder,onlyIds=ids).items
        return query {
            user(id(userId))
            val progress=ProcessingJobsTable.select {ProcessingJobsTable.videoId inList items.map {id(it.id)}}
                .associate {it[ProcessingJobsTable.videoId].toString() to it[ProcessingJobsTable.progressPercent]}
            items.map {it.copy(processingPercent=progress[it.id])}
        }
    }
    suspend fun editDetails(ownerId: String,videoId: String,request: EditVideoRequest): VideoDetails {
        val title=request.title.trim()
        require(title.isNotEmpty() && title.length<=128) { "Video title must be between 1 and 128 characters" }
        require((request.description?.length ?: 0)<=5000) { "Description must be at most 5000 characters" }
        val description=request.description?.trim()?.takeIf { it.isNotEmpty() }
        query {
            val parsed=id(videoId); owned(id(ownerId),parsed)
            VideosTable.update({ VideosTable.id eq parsed }) {
                it[VideosTable.title]=title; it[VideosTable.description]=description; it[updatedAt]=Instant.now()
            }
        }
        return details(ownerId,videoId)
    }
    suspend fun library(userId: String): List<LibraryVideo> = query {
        val owner=id(userId); user(owner)
        VideosTable.select { (VideosTable.uploaderId eq owner) and VideosTable.deletedAt.isNull() }.orderBy(VideosTable.createdAt,SortOrder.DESC).limit(50).map {
            LibraryVideo(it[VideosTable.id].toString(),it[VideosTable.title],it[VideosTable.publicationState],it[VideosTable.durationSeconds],it[VideosTable.visibility],it[VideosTable.isPublished],it[VideosTable.thumbnailObjectKey]!=null,it[VideosTable.folderId]?.toString(),it[VideosTable.updatedAt].toEpochMilli().toString())
        }
    }
    suspend fun details(userId: String?,videoId: String,token: String?=null): VideoDetails = query {
        val viewer=userId?.let { id(it) }; val row=video(viewer,id(videoId),token)
        VideoDetails(row[VideosTable.id].toString(),row[VideosTable.title],row[VideosTable.description],row[VideosTable.publicationState],
            row[VideosTable.durationSeconds],row[VideosTable.visibility],row[VideosTable.isPublished],row[VideosTable.uploaderId]==viewer,
            row[VideosTable.publicationState]=="PROCESSED",user(row[VideosTable.uploaderId])[UsersTable.displayName],if(row[VideosTable.uploaderId]==viewer) row[VideosTable.folderId]?.toString() else null,row[VideosTable.updatedAt].toEpochMilli().toString(),
            if(row[VideosTable.uploaderId]==viewer) ProcessingJobsTable.select {ProcessingJobsTable.videoId eq row[VideosTable.id]}.singleOrNull()?.get(ProcessingJobsTable.progressPercent) else null)
    }
    // Compatibility for older clients: never list any video or metadata.
    @Suppress("UNUSED_PARAMETER")
    suspend fun discover(userId: String,cursor: String?,limit: Int=20): DiscoveryPage = query {
        require(limit in 1..50) { "Page size must be between 1 and 50" }
        user(id(userId))
        DiscoveryPage(emptyList(),null)
    }
    suspend fun publish(ownerId: String,videoId: String,visibility: String): VideoDetails {
        require(visibility == "UNLISTED") { "Only link sharing is supported; update the app and choose Enable link sharing" }
        // Check the private master before taking a row lock; never publish just from an upload status.
        query { if(owned(id(ownerId),id(videoId))[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable() }
        asset(ownerId,videoId,"master.m3u8")
        query {
            val row=owned(id(ownerId),id(videoId))
            if(row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
            val changed=!row[VideosTable.isPublished] || row[VideosTable.visibility]!=visibility
            VideosTable.update({ VideosTable.id eq id(videoId) }) {
                it[isPublished]=true; it[VideosTable.visibility]=visibility
                it[publishedAt]=if(changed) Instant.now() else row[VideosTable.publishedAt]
                if(changed) it[shareVersion]=UUID.randomUUID()
                it[updatedAt]=Instant.now()
            }
        }
        return details(ownerId,videoId)
    }
    suspend fun unpublish(ownerId: String,videoId: String): VideoDetails {
        query {
            owned(id(ownerId),id(videoId))
            VideosTable.update({ VideosTable.id eq id(videoId) }) {
                it[isPublished]=false; it[publishedAt]=null; it[shareVersion]=UUID.randomUUID(); it[updatedAt]=Instant.now()
            }
        }
        return details(ownerId,videoId)
    }
    suspend fun shareLink(ownerId: String,videoId: String): VideoShareLink = query {
        val row=owned(id(ownerId),id(videoId))
        if(!row[VideosTable.isPublished] || row[VideosTable.publishedAt]==null || row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
        val configured=System.getenv("PUBLIC_SHARE_ORIGIN").orEmpty().trimEnd('/')
        if(configured.isNotEmpty()) {
            val uri=java.net.URI(configured)
            check(uri.scheme=="https" && uri.host!=null && uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.rawPath.isNullOrEmpty()) {"Invalid share origin"}
        }
        val base=if(configured.isEmpty()) "vidoora://watch/${row[VideosTable.id]}" else "$configured/watch/${row[VideosTable.id]}"
        when(row[VideosTable.visibility]) {
            "PUBLIC" -> throw PlaybackDenied()
            "UNLISTED" -> {
                val expires=Instant.ofEpochSecond(Instant.now().epochSecond + 30L*86400)
                val token=checkNotNull(shareTokens).issueCompact(row[VideosTable.id],row[VideosTable.shareVersion],expires)
                VideoShareLink("$base#$token",expires.toString())
            }
            else -> throw PlaybackDenied()
        }
    }
    suspend fun changeThumbnail(userId: String,videoId: String,bytes: ByteArray): VideoDetails {
        val parsed=id(videoId); val owner=id(userId)
        query { if(owned(owner,parsed)[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable() }
        val clean=withContext(Dispatchers.IO) { ThumbnailImages.sanitize(bytes) }
        reads.withPermit { query {
            val row=owned(owner,parsed)
            if(row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
            val bucket=row[VideosTable.processedBucket] ?: throw PlaybackUnavailable()
            val master=row[VideosTable.masterPlaylistKey] ?: throw PlaybackUnavailable()
            if(!Regex("processed/$parsed/[0-9a-f-]{36}/master\\.m3u8").matches(master)) throw PlaybackUnavailable()
            val key=master.removeSuffix("master.m3u8")+"custom-thumbnail.jpg"
            storage.uploadThumbnail(bucket,key,clean)
            VideosTable.update({ VideosTable.id eq parsed }) {
                it[thumbnailObjectKey]=key; it[thumbnailWorkerId]=null; it[thumbnailLeaseUntil]=null; it[updatedAt]=Instant.now()
            }
        } }
        return details(userId,videoId)
    }
    suspend fun thumbnail(userId: String?,videoId: String,shareToken: String?=null): ByteArray {
        val parsed=id(videoId)
        val location=query {
            val row=video(userId?.let { id(it) },parsed,shareToken)
            if(row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
            val bucket=row[VideosTable.processedBucket] ?: throw PlaybackUnavailable()
            val master=row[VideosTable.masterPlaylistKey] ?: throw PlaybackUnavailable()
            val key=row[VideosTable.thumbnailObjectKey] ?: throw PlaybackUnavailable()
            if(!Regex("processed/$parsed/[0-9a-f-]{36}/master\\.m3u8").matches(master) || key !in listOf("thumbnail.jpg","custom-thumbnail.jpg").map { master.removeSuffix("master.m3u8")+it }) throw PlaybackUnavailable()
            bucket to key
        }
        return reads.withPermit { withContext(Dispatchers.IO) {
            try {
                storage.readProcessed(location.first,location.second,256*1024).also {
                    check(it.size in 4..256*1024 && it[0]==0xff.toByte() && it[1]==0xd8.toByte())
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { throw PlaybackUnavailable() }
        } }
    }
    suspend fun offlineDeadline(userId: String,videoId: String,token: String?): Instant?=query {
        val viewer=id(userId);val parsed=id(videoId);val row=video(viewer,parsed,token)
        if(row[VideosTable.uploaderId]==viewer) null
        else shareTokens?.expiry(token,parsed,row[VideosTable.shareVersion]) ?: throw PlaybackDenied()
    }
    suspend fun contentVersion(userId: String,videoId: String,token: String?): String=query {
        val row=video(id(userId),id(videoId),token)
        if(row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
        val value=(row[VideosTable.processedBucket] ?: throw PlaybackUnavailable())+"|"+(row[VideosTable.masterPlaylistKey] ?: throw PlaybackUnavailable())
        java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
    internal data class AuthorizedAsset(val bucket: String,val key: String,val maxHeight: Int?)
    internal suspend fun authorizeAsset(userId: String?,videoId: String,path: String,shareToken: String?=null): AuthorizedAsset {
        if(!PlaybackPaths.validAsset(path)) throw PlaybackDenied()
        val videoIdParsed=id(videoId)
        val location=query {
            val row=video(userId?.let { id(it) },videoIdParsed,shareToken)
            if(row[VideosTable.publicationState]!="PROCESSED") throw PlaybackUnavailable()
            val master=row[VideosTable.masterPlaylistKey] ?: throw PlaybackUnavailable()
            val bucket=row[VideosTable.processedBucket] ?: throw PlaybackUnavailable()
            if(!Regex("processed/$videoIdParsed/[0-9a-f-]{36}/master\\.m3u8").matches(master)) throw PlaybackUnavailable()
            bucket to master.removeSuffix("master.m3u8")
        }
        // Authorization above always precedes plan lookup; paid plans never grant private-video access.
        val benefits=entitlements.resolve(userId)
        // Store verification may wait on the network. Sharing/deletion/block changes during it must win.
        query {
            val current=video(userId?.let { id(it) },videoIdParsed,shareToken)
            if(current[VideosTable.publicationState]!="PROCESSED" || current[VideosTable.processedBucket]!=location.first ||
                current[VideosTable.masterPlaylistKey]!=location.second+"master.m3u8") throw PlaybackUnavailable()
        }
        if(!PlaybackQuality.permits(path,benefits.maxHeight)) throw PlaybackQualityRestricted()
        return AuthorizedAsset(location.first,location.second+path,benefits.maxHeight)
    }
    suspend fun asset(userId: String?,videoId: String,path: String,shareToken: String?=null): ByteArray {
        val authorized=authorizeAsset(userId,videoId,path,shareToken)
        return reads.withPermit { withContext(Dispatchers.IO) {
            try {
                val limit=if(path.endsWith(".m3u8")) 256*1024 else 16*1024*1024
                val bytes=storage.readProcessed(authorized.bucket,authorized.key,limit)
                if(path.endsWith(".m3u8")) PlaybackPaths.validateManifest(path,bytes.toString(Charsets.UTF_8))
                if(path=="master.m3u8") PlaybackQuality.master(bytes.toString(Charsets.UTF_8),authorized.maxHeight).toByteArray(Charsets.UTF_8) else bytes
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: PlaybackQualityRestricted) { throw e }
            catch (_: Exception) { throw PlaybackUnavailable() }
        } }
    }
}















