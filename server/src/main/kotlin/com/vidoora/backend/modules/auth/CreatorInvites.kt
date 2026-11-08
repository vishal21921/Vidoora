package com.vidoora.backend.modules.auth

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import com.vidoora.backend.modules.user.UsersTable
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest

object CreatorInvites : Table("creator_invites") {
    val hash=varchar("token_hash",64)
    val expires=timestamp("expires_at")
    val redeemedBy=uuid("redeemed_by").references(UsersTable.id).nullable()
    val redeemedAt=timestamp("redeemed_at").nullable()
    val revoked=bool("revoked").default(false)
    override val primaryKey=PrimaryKey(hash)
    fun consume(code: String,user: UUID) {
        require(code.length in 16..128) { "Invite code is invalid or expired" }
        val digest=MessageDigest.getInstance("SHA-256").digest(code.trim().toByteArray()).joinToString(""){"%02x".format(it)}
        val row=select {hash eq digest}.forUpdate().singleOrNull()
        require(row!=null && !row[revoked] && row[redeemedBy]==null && row[expires]>Instant.now()) { "Invite code is invalid or expired" }
        update({hash eq digest}) {it[redeemedBy]=user;it[redeemedAt]=Instant.now()}
    }
}
