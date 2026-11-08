package com.vidoora.backend.modules.playback

import com.vidoora.backend.modules.user.UsersTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table

object VideoFoldersTable : Table("video_folders") {
    val id=uuid("id").autoGenerate()
    val ownerId=reference("owner_id",UsersTable.id)
    val name=varchar("name",64)
    val nameKey=varchar("name_key",128)
    override val primaryKey=PrimaryKey(id)
    init { uniqueIndex(ownerId,nameKey) }
}
@Serializable data class VideoFolder(val id: String,val name: String)
@Serializable data class FolderNameRequest(val name: String)
@Serializable data class MoveFolderRequest(val folderId: String?=null)
