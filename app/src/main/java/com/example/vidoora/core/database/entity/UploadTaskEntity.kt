package com.example.vidoora.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "upload_tasks")
data class UploadTaskEntity(
    @PrimaryKey val id: String,
    val videoId: String,
    val uploadSessionId: String,
    val sourceUri: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val title: String,
    val description: String?,
    val visibility: String,
    val status: String,
    val progressPercentage: Int = 0,
    val totalParts: Int,
    val completedPartsCount: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val userId: String = ""
)
