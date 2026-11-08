package com.vidoora.backend.modules.membership

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.user.UsersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.sql.Connection
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PlayPurchasesTable : Table("play_purchases") {
    val tokenHash = varchar("token_hash", 64)
    val userId = uuid("user_id").references(UsersTable.id)
    val encryptedToken = text("encrypted_token")
    val supportId = uuid("support_id").uniqueIndex()
    val planId = varchar("plan_id", 16).nullable()
    val periodId = varchar("period_id", 256).nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val state = varchar("state", 32)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

/** Key is server-only, separate from JWT secret. Hash is authenticated as AAD to prevent row/token swapping. */
internal class PurchaseTokenCipher(key: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 32) }, "AES")
    private val random = SecureRandom()
    fun encrypt(token: String): String {
        validateToken(token)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(purchaseDigest(token).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(value: String, digest: String): String {
        try {
            val bytes = Base64.getDecoder().decode(value)
            require(bytes.size in 29..16412)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(digest.toByteArray(Charsets.UTF_8))
            val token = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            validateToken(token)
            require(purchaseDigest(token) == digest)
            return token
        } catch (_: Exception) { throw PlayVerificationFailure("PURCHASE_STORAGE_INVALID") }
    }
}

internal interface PlayPurchaseLedger {
    suspend fun claim(account: UUID, token: String): UUID
    suspend fun claimLinked(account: UUID,token: String,linkedHash: String?): UUID {
        check(linkedHash==null) { "Linked receipts require chain-aware storage" }
        return claim(account,token)
    }
    suspend fun record(account: UUID, token: String, receipt: PlayReceipt?, state: String)
    suspend fun tokens(account: UUID): List<String>
}

/** All writes are short DB transactions; Google network calls never hold database locks. */
internal class PostgresPlayPurchaseLedger(private val cipher: PurchaseTokenCipher,
    private val connectionFactory: (() -> Connection)? = null) : PlayPurchaseLedger {
    private fun <T> transaction(block: (Connection) -> T): T {
        val factory = connectionFactory ?: return DatabaseFactory.jdbcTransaction(block)
        return factory().use { connection ->
            connection.autoCommit = false
            try { block(connection).also { connection.commit() } }
            catch (e: Throwable) { connection.rollback(); throw e }
        }
    }
    override suspend fun claim(account: UUID, token: String): UUID = claimLinked(account,token,null)
    override suspend fun claimLinked(account: UUID,token: String,linkedHash: String?): UUID = withContext(Dispatchers.IO) {
        val hash = purchaseDigest(token)
        val encrypted = cipher.encrypt(token)
        transaction { db ->
            // Serializes claims for this account and rejects suspended/deleted users.
            db.prepareStatement("SELECT id FROM users WHERE id=? AND is_suspended=false FOR UPDATE").use { s ->
                s.setObject(1, account); s.executeQuery().use { if (!it.next()) throw PlayVerificationFailure("ACCOUNT_UNAVAILABLE") }
            }
            for(value in listOfNotNull(hash,linkedHash).distinct().sorted()) {
                db.prepareStatement("INSERT INTO play_token_bindings(token_hash,user_id) VALUES (?,?) ON CONFLICT (token_hash) DO NOTHING").use {s ->
                    s.setString(1,value);s.setObject(2,account);s.executeUpdate()
                }
                db.prepareStatement("SELECT user_id,superseded FROM play_token_bindings WHERE token_hash=? FOR UPDATE").use {s ->
                    s.setString(1,value);s.executeQuery().use {r ->
                        check(r.next())
                        if(r.getObject(1,UUID::class.java)!=account) throw PlayVerificationFailure("PURCHASE_ACCOUNT_MISMATCH")
                        if(value==hash && r.getBoolean(2)) throw PlayVerificationFailure("PURCHASE_SUPERSEDED")
                    }
                }
            }
            if(linkedHash!=null) db.prepareStatement("UPDATE play_token_bindings SET superseded=true WHERE token_hash=? AND user_id=?").use {s ->
                s.setString(1,linkedHash);s.setObject(2,account);s.executeUpdate()
            }
            val support = UUID.randomUUID()
            db.prepareStatement("""INSERT INTO play_purchases(token_hash,user_id,encrypted_token,support_id,state,updated_at)
                VALUES (?,?,?,?,'RECEIVED',CURRENT_TIMESTAMP) ON CONFLICT (token_hash) DO NOTHING""").use { s ->
                s.setString(1, hash); s.setObject(2, account); s.setString(3, encrypted); s.setObject(4, support); s.executeUpdate()
            }
            db.prepareStatement("SELECT user_id,support_id FROM play_purchases WHERE token_hash=?").use { s ->
                s.setString(1, hash)
                s.executeQuery().use { r ->
                    check(r.next())
                    if (r.getObject("user_id", UUID::class.java) != account) throw PlayVerificationFailure("PURCHASE_ACCOUNT_MISMATCH")
                    r.getObject("support_id", UUID::class.java)
                }
            }
        }
    }

    override suspend fun record(account: UUID, token: String, receipt: PlayReceipt?, state: String) = withContext(Dispatchers.IO) {
        require(state in setOf("VERIFIED", "ACKNOWLEDGED", "INACTIVE"))
        if(state!="INACTIVE") {
            require(receipt!=null && receipt.accountId==account && receipt.tokenDigest==purchaseDigest(token))
            require(state!="ACKNOWLEDGED" || receipt.acknowledged)
        }
        transaction { db ->
            db.prepareStatement("""UPDATE play_purchases SET plan_id=?,period_id=?,expires_at=?,state=?,updated_at=CURRENT_TIMESTAMP
                WHERE token_hash=? AND user_id=?""").use { s ->
                s.setString(1, receipt?.plan?.planId); s.setString(2, receipt?.periodId)
                s.setTimestamp(3, receipt?.expiresAt?.let { java.sql.Timestamp.from(it) })
                s.setString(4, state); s.setString(5, purchaseDigest(token)); s.setObject(6, account)
                check(s.executeUpdate() == 1)
            }
        }
        Unit
    }

    override suspend fun tokens(account: UUID): List<String> = withContext(Dispatchers.IO) {
        transaction { db ->
            db.prepareStatement("""SELECT p.token_hash,p.encrypted_token FROM play_purchases p JOIN play_token_bindings b ON b.token_hash=p.token_hash
                WHERE p.user_id=? AND p.state='ACKNOWLEDGED' AND b.superseded=false ORDER BY p.updated_at DESC LIMIT 32""").use { s ->
                s.setObject(1, account)
                s.executeQuery().use { r -> buildList { while (r.next()) add(cipher.decrypt(r.getString(2), r.getString(1))) } }
            }
        }
    }
}
