package com.example.vidoora.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val isSuspended: Boolean,
    val role: String,
    val createdAt: String,
    @androidx.room.ColumnInfo(defaultValue="''") val publicId: String = ""
)
