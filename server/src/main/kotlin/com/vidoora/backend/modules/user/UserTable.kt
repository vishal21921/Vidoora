package com.vidoora.backend.modules.user

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

object UsersTable : Table("users") {
    val id = uuid("id").autoGenerate()
    val publicNumber = long("public_number").autoIncrement().uniqueIndex()
    val username = varchar("username", 32).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 64)
    val avatarUrl = text("avatar_url").nullable()
    val bio = varchar("bio", 255).nullable()
    val isSuspended = bool("is_suspended").default(false)
    val role = varchar("role", 16).default("USER")
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val isSuspended: Boolean = false,
    val role: String = "USER",
    val createdAt: String,
    val publicId: String = ""
)
