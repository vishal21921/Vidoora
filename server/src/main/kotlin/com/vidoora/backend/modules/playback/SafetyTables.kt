package com.vidoora.backend.modules.playback

import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.video.VideosTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object ReportsTable : Table("reports") {
    val id=uuid("id").autoGenerate()
    val reporterId=reference("reporter_id",UsersTable.id)
    val videoId=reference("video_id",VideosTable.id).nullable()
    val targetUserId=reference("target_user_id",UsersTable.id).nullable()
    val reason=varchar("reason",32)
    val details=text("details").nullable()
    val status=varchar("status",16).default("PENDING")
    val createdAt=timestamp("created_at").clientDefault { Instant.now() }
    override val primaryKey=PrimaryKey(id)
}
object UserBlocksTable : Table("user_blocks") {
    val blockerId=reference("blocker_id",UsersTable.id)
    val blockedId=reference("blocked_id",UsersTable.id)
    val createdAt=timestamp("created_at").clientDefault { Instant.now() }
    override val primaryKey=PrimaryKey(blockerId,blockedId)
}
