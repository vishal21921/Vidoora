package com.example.vidoora.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "upload_parts",
    primaryKeys = ["taskId", "partNumber"],
    foreignKeys = [
        ForeignKey(
            entity = UploadTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"])]
)
data class UploadPartEntity(
    val taskId: String,
    val partNumber: Int,
    val uploadUrl: String,
    val startByte: Long,
    val endByte: Long,
    val sizeBytes: Long,
    val status: String,
    val eTag: String? = null,
    val retryCount: Int = 0
)
