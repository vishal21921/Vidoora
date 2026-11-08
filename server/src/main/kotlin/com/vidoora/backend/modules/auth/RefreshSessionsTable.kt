package com.vidoora.backend.modules.auth

import com.vidoora.backend.modules.user.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object RefreshSessionsTable : Table("refresh_sessions") {
    val id=uuid("id")
    val userId=uuid("user_id").references(UsersTable.id)
    val tokenHash=varchar("token_hash",64)
    val createdAt=timestamp("created_at")
    val expiresAt=timestamp("expires_at")
    val lastUsedAt=timestamp("last_used_at").nullable()
    val revokedAt=timestamp("revoked_at").nullable()
    override val primaryKey=PrimaryKey(id)
}

class InvalidRefreshToken : IllegalArgumentException("Session expired or no longer valid. Please sign in again.")
