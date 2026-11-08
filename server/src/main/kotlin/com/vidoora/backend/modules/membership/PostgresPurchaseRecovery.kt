package com.vidoora.backend.modules.membership

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.user.UsersTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal object PlayTokenBindings : Table("play_token_bindings") {
    val hash=varchar("token_hash",64);val user=uuid("user_id").references(UsersTable.id);val superseded=bool("superseded").default(false)
    override val primaryKey=PrimaryKey(hash)
}
internal object PurchaseAttempts : Table("purchase_attempts") {
    val id=uuid("id");val user=uuid("user_id").references(UsersTable.id);val hash=varchar("token_hash",64);val token=text("encrypted_token")
    val payment=varchar("payment_state",16);val activation=varchar("activation_state",16);val plan=varchar("plan_id",16).nullable()
    val period=varchar("period_hash",64).nullable()
    val expiry=timestamp("expires_at").nullable();val error=varchar("last_error",64).nullable();val tries=integer("retry_count").default(0)
    val next=timestamp("next_attempt_at").nullable();val lease=uuid("lease_id").nullable();val leaseUntil=timestamp("lease_until").nullable()
    val created=timestamp("created_at");val updated=timestamp("updated_at")
    override val primaryKey=PrimaryKey(id)
    init {uniqueIndex(user,hash)}
}
internal object PurchaseEvents : Table("purchase_events") {
    val id=uuid("id");val attempt=uuid("attempt_id").references(PurchaseAttempts.id);val user=uuid("user_id").references(UsersTable.id)
    val period=varchar("period_hash",64).uniqueIndex();val plan=varchar("plan_id",16);val expiry=timestamp("expires_at");val created=timestamp("created_at")
    override val primaryKey=PrimaryKey(id)
}

internal class PostgresPurchaseRecovery(private val cipher: PurchaseTokenCipher,private val connections: (() -> Connection)?=null) : PurchaseRecoveryStore {
    private suspend fun <T> query(block: (Connection)->T): T=withContext(Dispatchers.IO) {
        if(connections==null) DatabaseFactory.jdbcTransaction(block)
        else connections.invoke().use { db -> db.autoCommit=false;try {block(db).also{db.commit()}} catch(e: Throwable){db.rollback();throw e} }
    }
    override suspend fun enqueue(account: UUID,token: String,now: Instant): PaymentRecord = query { db ->
        db.prepareStatement("SELECT id FROM users WHERE id=? AND is_suspended=false FOR UPDATE").use {s ->
            s.setObject(1,account);s.executeQuery().use {if(!it.next()) throw PaymentNotFound()}
        }
        val hash=purchaseDigest(token)
        val existing=db.prepareStatement("SELECT * FROM purchase_attempts WHERE user_id=? AND token_hash=?").use {s ->
            s.setObject(1,account);s.setString(2,hash);s.executeQuery().use {r -> if(r.next()) record(r) else null}
        }
        if(existing!=null) {
            // Redelivery cannot bypass an existing retry/pending backoff or an in-flight lease.
            if(existing.activationState in listOf("REJECTED","INACTIVE","ACTIVE") && Instant.parse(existing.updatedAt).plusSeconds(30)<=now) {
                db.prepareStatement("UPDATE purchase_attempts SET activation_state='RECEIVED',next_attempt_at=?,updated_at=? WHERE id=? AND (lease_until IS NULL OR lease_until<=?)").use {s ->
                    s.setTimestamp(1,Timestamp.from(now));s.setTimestamp(2,Timestamp.from(now));s.setObject(3,UUID.fromString(existing.reference));s.setTimestamp(4,Timestamp.from(now));s.executeUpdate()
                }
            }
            return@query find(db,account,UUID.fromString(existing.reference))
        }
        db.prepareStatement("SELECT count(*) FILTER (WHERE created_at>?),count(*) FILTER (WHERE activation_state IN ('RECEIVED','PENDING','RETRYING')) FROM purchase_attempts WHERE user_id=?").use {s ->
            s.setTimestamp(1,Timestamp.from(now.minusSeconds(86400)));s.setObject(2,account)
            s.executeQuery().use {r ->r.next();if(r.getLong(1)>=32 || r.getLong(2)>=5) throw PaymentRateLimited()}
        }
        val id=UUID.randomUUID()
        db.prepareStatement("""INSERT INTO purchase_attempts(id,user_id,token_hash,encrypted_token,payment_state,activation_state,next_attempt_at,created_at,updated_at)
            VALUES (?,?,?,?,'UNKNOWN','RECEIVED',?,?,?)""").use {s ->
            s.setObject(1,id);s.setObject(2,account);s.setString(3,hash);s.setString(4,cipher.encrypt(token))
            for(i in 5..7) s.setTimestamp(i,Timestamp.from(now));s.executeUpdate()
        }
        find(db,account,id)
    }
    override suspend fun claim(now: Instant): RecoveryClaim?=query {db ->
        db.prepareStatement("""SELECT p.* FROM purchase_attempts p JOIN users u ON u.id=p.user_id
            WHERE u.is_suspended=false AND p.next_attempt_at<=? AND (p.lease_until IS NULL OR p.lease_until<=?)
            ORDER BY p.next_attempt_at,p.id LIMIT 1 FOR UPDATE OF p SKIP LOCKED""").use {s ->
            s.setTimestamp(1,Timestamp.from(now));s.setTimestamp(2,Timestamp.from(now))
            s.executeQuery().use {r ->
                if(!r.next()) return@query null
                val id=r.getObject("id",UUID::class.java);val lease=UUID.randomUUID()
                val result=RecoveryClaim(id,r.getObject("user_id",UUID::class.java),cipher.decrypt(r.getString("encrypted_token"),r.getString("token_hash")),
                    lease,r.getInt("retry_count")+1,r.getString("payment_state")=="SUCCESS")
                db.prepareStatement("UPDATE purchase_attempts SET lease_id=?,lease_until=?,retry_count=retry_count+1 WHERE id=?").use {q ->
                    q.setObject(1,lease);q.setTimestamp(2,Timestamp.from(now.plusSeconds(300)));q.setObject(3,id);q.executeUpdate()
                }
                result
            }
        }
    }
    override suspend fun finish(claim: RecoveryClaim,result: RecoveryResult,now: Instant): Boolean=query {db ->
        db.prepareStatement("SELECT id FROM purchase_attempts WHERE id=? AND user_id=? AND lease_id=? AND lease_until>? FOR UPDATE").use {s ->
            s.setObject(1,claim.id);s.setObject(2,claim.account);s.setObject(3,claim.lease);s.setTimestamp(4,Timestamp.from(now))
            s.executeQuery().use {if(!it.next()) return@query false}
        }
        result.receipt?.let {receipt ->
            check(receipt.accountId==claim.account && receipt.acknowledged && receipt.tokenDigest==purchaseDigest(claim.token))
            db.prepareStatement("UPDATE purchase_attempts SET period_hash=? WHERE id=?").use {s ->
                s.setString(1,purchaseDigest(receipt.periodId));s.setObject(2,claim.id);s.executeUpdate()
            }
            val first=db.prepareStatement("SELECT id FROM purchase_events WHERE attempt_id=? LIMIT 1").use {s ->
                s.setObject(1,claim.id);s.executeQuery().use { !it.next() }
            }
            db.prepareStatement("""INSERT INTO purchase_events(id,attempt_id,user_id,period_hash,plan_id,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (period_hash) DO NOTHING""").use {s ->
                s.setObject(1,if(first) claim.id else UUID.randomUUID());s.setObject(2,claim.id);s.setObject(3,claim.account)
                s.setString(4,purchaseDigest(receipt.periodId));s.setString(5,receipt.plan.planId);s.setTimestamp(6,Timestamp.from(receipt.expiresAt))
                s.setTimestamp(7,Timestamp.from(now));s.executeUpdate()
            }
        }
        db.prepareStatement("""UPDATE purchase_attempts SET payment_state=CASE WHEN payment_state='SUCCESS' THEN 'SUCCESS' ELSE ? END,
            activation_state=?,plan_id=COALESCE(?,plan_id),expires_at=COALESCE(?,expires_at),last_error=?,next_attempt_at=?,
            retry_count=CASE WHEN ?='ACTIVE' THEN 0 ELSE retry_count END,lease_id=NULL,lease_until=NULL,updated_at=? WHERE id=?""").use {s ->
            s.setString(1,result.payment);s.setString(2,result.activation);s.setString(3,result.receipt?.plan?.planId)
            s.setTimestamp(4,result.receipt?.expiresAt?.let(Timestamp::from));s.setString(5,result.error)
            s.setTimestamp(6,result.next?.let(Timestamp::from));s.setString(7,result.activation);s.setTimestamp(8,Timestamp.from(now));s.setObject(9,claim.id);s.executeUpdate()
        }
        true
    }
    override suspend fun retry(account: UUID,reference: UUID,now: Instant): PaymentRecord=query {db ->
        val attemptId=db.prepareStatement("SELECT id FROM purchase_attempts WHERE user_id=? AND id=? UNION ALL SELECT attempt_id FROM purchase_events WHERE user_id=? AND id=? LIMIT 1").use {s ->
            s.setObject(1,account);s.setObject(2,reference);s.setObject(3,account);s.setObject(4,reference)
            s.executeQuery().use {r ->if(!r.next()) throw PaymentNotFound();r.getObject(1,UUID::class.java)}
        }
        val current=find(db,account,attemptId)
        if(current.retryable && Instant.parse(current.updatedAt).plusSeconds(30)<=now) {
            db.prepareStatement("""UPDATE purchase_attempts SET next_attempt_at=LEAST(COALESCE(next_attempt_at,?),?),updated_at=?
                WHERE user_id=? AND id=? AND (lease_until IS NULL OR lease_until<=?)""").use {s ->
                s.setTimestamp(1,Timestamp.from(now.plusSeconds(30)));s.setTimestamp(2,Timestamp.from(now.plusSeconds(30)))
                s.setTimestamp(3,Timestamp.from(now));s.setObject(4,account);s.setObject(5,attemptId);s.setTimestamp(6,Timestamp.from(now));s.executeUpdate()
            }
        }
        current
    }
    override suspend fun page(account: UUID,cursor: UUID?): PaymentPage=query {db ->
        // Stable account-scoped creation-time/UUID keyset. Provider order IDs and tokens never leave the server.
        val union="""SELECT p.id,p.payment_state,p.activation_state,p.plan_id,p.expires_at,p.created_at,p.updated_at
            FROM purchase_attempts p WHERE p.user_id=? AND NOT EXISTS(SELECT 1 FROM purchase_events e WHERE e.attempt_id=p.id OR (e.period_hash=p.period_hash AND e.user_id=p.user_id))
            UNION ALL SELECT e.id,'SUCCESS',CASE WHEN e.expires_at<=CURRENT_TIMESTAMP THEN 'INACTIVE' ELSE p.activation_state END,
            e.plan_id,e.expires_at,e.created_at,p.updated_at FROM purchase_events e JOIN purchase_attempts p ON e.attempt_id=p.id WHERE e.user_id=?"""
        val cursorTime=cursor?.let {id -> db.prepareStatement("SELECT created_at FROM ($union) records WHERE id=?").use {s ->
            s.setObject(1,account);s.setObject(2,account);s.setObject(3,id);s.executeQuery().use {r ->if(!r.next()) throw PaymentNotFound();r.getTimestamp(1)}
        } }
        val where=if(cursor==null) "" else "WHERE (created_at,id)<(?,?)"
        val items=db.prepareStatement("SELECT * FROM ($union) records $where ORDER BY created_at DESC,id DESC LIMIT 26").use {s ->
            s.setObject(1,account);s.setObject(2,account)
            if(cursor!=null) {s.setTimestamp(3,cursorTime);s.setObject(4,cursor)}
            s.executeQuery().use {r ->buildList {while(r.next()) add(record(r))}}
        }
        PaymentPage(true,items.take(25),if(items.size>25) items[24].reference else null)
    }
    private fun find(db: Connection,account: UUID,id: UUID): PaymentRecord = db.prepareStatement("SELECT * FROM purchase_attempts WHERE user_id=? AND id=?").use {s ->
        s.setObject(1,account);s.setObject(2,id);s.executeQuery().use {r ->if(!r.next()) throw PaymentNotFound();record(r)}
    }
    private fun record(r: ResultSet)=PaymentRecord(r.getObject("id").toString(),r.getString("payment_state"),r.getString("activation_state"),
        r.getString("plan_id"),r.getTimestamp("expires_at")?.toInstant()?.toString(),r.getTimestamp("created_at").toInstant().toString(),
        r.getTimestamp("updated_at").toInstant().toString(),r.getString("activation_state") in listOf("RETRYING","PENDING"))
}
