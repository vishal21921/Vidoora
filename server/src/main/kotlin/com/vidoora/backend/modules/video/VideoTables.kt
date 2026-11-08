package com.vidoora.backend.modules.video

import com.vidoora.backend.modules.user.UsersTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object VideosTable : Table("videos") {
    val id = uuid("id").autoGenerate()
    val uploaderId = reference("uploader_id", UsersTable.id)
    val folderId = reference("folder_id", com.vidoora.backend.modules.playback.VideoFoldersTable.id).nullable()
    val title = varchar("title", 128)
    val description = text("description").nullable()
    val durationSeconds = integer("duration_seconds").default(0)
    val publicationState = varchar("publication_state", 24).default("CREATED")
    val visibility = varchar("visibility", 16).default("PRIVATE")
    val rawObjectKey = text("raw_object_key").nullable()
    val masterPlaylistUrl = text("master_playlist_url").nullable()
    val processedBucket = text("processed_bucket").nullable()
    val masterPlaylistKey = text("master_playlist_key").nullable()
    val deletedAt = timestamp("deleted_at").nullable()
    val storageDeletedAt = timestamp("storage_deleted_at").nullable()
    val deletionCleanupToken = uuid("deletion_cleanup_token").nullable()
    val deletionCleanupLeaseUntil = timestamp("deletion_cleanup_lease_until").nullable()
    val deletionCleanupNextAt = timestamp("deletion_cleanup_next_at").clientDefault { Instant.now() }
    val deletionCleanupAttempts = integer("deletion_cleanup_attempts").default(0)
    val thumbnailObjectKey = text("thumbnail_object_key").nullable()
    val thumbnailWorkerId = uuid("thumbnail_worker_id").nullable()
    val thumbnailLeaseUntil = timestamp("thumbnail_lease_until").nullable()
    val thumbnailRetryCount = integer("thumbnail_retry_count").default(0)
    val thumbnailNextAttemptAt = timestamp("thumbnail_next_attempt_at").clientDefault { Instant.now() }
    val thumbnailUrl = text("thumbnail_url").nullable()
    val viewsCount = long("views_count").default(0L)
    val createdAt = timestamp("created_at").default(Instant.now())
    val publishedAt = timestamp("published_at").nullable()
    val isPublished = bool("is_published").default(false)
    val shareVersion = uuid("share_version").clientDefault { java.util.UUID.randomUUID() }
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

object UploadSessionsTable : Table("upload_sessions") {
    val id = uuid("id").autoGenerate()
    val videoId = reference("video_id", VideosTable.id)
    val uploaderId = reference("uploader_id", UsersTable.id)
    val uploadIdProvider = text("upload_id_provider")
    val objectKey = text("object_key")
    val totalBytes = long("total_bytes")
    val chunkSizeByes = integer("chunk_size_bytes").default(5242880)
    val completedParts = jsonb<List<String>>("completed_parts", Json, ListSerializer(String.serializer())).default(emptyList())
    val status = varchar("status", 20).default("ACTIVE")
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

object ProcessingJobsTable : Table("processing_jobs") {
    val leaseUntil = timestamp("lease_until").nullable()
    val id = uuid("id").autoGenerate()
    val videoId = reference("video_id", VideosTable.id)
    val status = varchar("status", 20).default("QUEUED")
    val progressPercent = integer("progress_percent").default(0)
    val retryCount = integer("retry_count").default(0)
    val maxRetries = integer("max_retries").default(3)
    val errorMessage = text("error_message").nullable()
    val workerId = varchar("worker_id", 64).nullable()
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class VideoDto(
    val id: String,
    val uploaderId: String,
    val title: String,
    val description: String? = null,
    val durationSeconds: Int = 0,
    val publicationState: String,
    val visibility: String,
    val masterPlaylistUrl: String? = null,
    val thumbnailUrl: String? = null,
    val viewsCount: Long = 0,
    val createdAt: String
)






