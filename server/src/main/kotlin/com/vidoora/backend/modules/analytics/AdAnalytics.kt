package com.vidoora.backend.modules.analytics

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.playback.PlaybackDenied
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class AdSdkEvent(val requestId: String, val deviceKey: String, val format: String, val event: String) {
    override fun toString() = "AdSdkEvent(<redacted>)"
}
@Serializable data class AdFormatCounts(val format: String, val requests: Long, val loaded: Long,
    val impressions: Long, val clicks: Long, val failures: Long)
@Serializable data class AdTrend(val time: String, val interstitial: Long, val banner: Long)
@Serializable data class AdRecent(val time: String, val format: String, val event: String)
@Serializable data class AdReport(val from: String, val to: String, val updatedAt: String,
    val formats: List<AdFormatCounts>, val devices: Long, val trend: List<AdTrend>, val recent: List<AdRecent>,
    val earnings: AdminRevenue? = null,
    val testOnly: Boolean = true, val source: String = "Client-reported SDK callbacks; not verified AdMob reporting")
@Serializable data class GeneratedCreatorInvite(val code: String, val expiresAt: String) {
    override fun toString() = "GeneratedCreatorInvite(<redacted>)"
}

/** Privacy-bounded operational estimates, completely separate from ad billing. */
class AdAnalytics(secret: String, private val clock: Clock = Clock.systemUTC()) {
    private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun hash(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(key); doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun admin(c: java.sql.Connection, user: String, update: Boolean = false) {
        c.prepareStatement("SELECT 1 FROM users WHERE id=? AND role='ADMIN' AND is_suspended=FALSE " + if(update) "FOR UPDATE" else "FOR SHARE").use {
            it.setObject(1, UUID.fromString(user)); it.executeQuery().use { rows -> if (!rows.next()) throw PlaybackDenied() }
        }
    }
    suspend fun record(input: AdSdkEvent) = withContext(Dispatchers.IO) {
        val id = UUID.fromString(input.requestId)
        UUID.fromString(input.deviceKey)
        require(input.format in setOf("INTERSTITIAL", "BANNER"))
        require(input.event in setOf("REQUEST", "LOADED", "IMPRESSION", "CLICK", "FAILED", "SHOW_FAILED"))
        val device = hash("ad-device:" + input.deviceKey)
        DatabaseFactory.jdbcTransaction { c ->
            // Only REQUEST creates an activity; subsequent callbacks must match it and arrive within 2h.
            val sql = if (input.event == "REQUEST") """
                INSERT INTO ad_sdk_events(request_id,event,format,device_hash,recorded_at) VALUES (?,?,?,?,?)
                ON CONFLICT DO NOTHING
            """ else """
                INSERT INTO ad_sdk_events(request_id,event,format,device_hash,recorded_at)
                SELECT ?,?,?,?,? WHERE EXISTS (SELECT 1 FROM ad_sdk_events WHERE request_id=? AND event='REQUEST'
                  AND format=? AND device_hash=? AND recorded_at>?) ON CONFLICT DO NOTHING
            """
            c.prepareStatement(sql).use { s ->
                s.setObject(1,id); s.setString(2,input.event); s.setString(3,input.format); s.setString(4,device)
                s.setObject(5,clock.instant().atOffset(ZoneOffset.UTC))
                if(input.event != "REQUEST") { s.setObject(6,id); s.setString(7,input.format); s.setString(8,device)
                    s.setObject(9,clock.instant().minusSeconds(7200).atOffset(ZoneOffset.UTC)) }
                s.executeUpdate()
            }
        }
    }
    suspend fun invite(user: String): GeneratedCreatorInvite = withContext(Dispatchers.IO) {
        DatabaseFactory.jdbcTransaction { c ->
            admin(c,user,true)
            // Serialize generation for this account; at most ten new codes per five minutes.
            c.prepareStatement("SELECT count(*) FROM creator_invites WHERE created_by=? AND created_at>?").use {
                it.setObject(1,UUID.fromString(user)); it.setObject(2,clock.instant().minusSeconds(300).atOffset(ZoneOffset.UTC))
                it.executeQuery().use { r -> r.next(); require(r.getInt(1)<10) { "Invite limit reached. Try again in five minutes." } }
            }
            val code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
            val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
            val expiry = clock.instant().plusSeconds(300)
            c.prepareStatement("INSERT INTO creator_invites(token_hash,expires_at,created_at,created_by) VALUES (?,?,?,?)").use {
                it.setString(1,digest); it.setObject(2,expiry.atOffset(ZoneOffset.UTC)); it.setObject(3,clock.instant().atOffset(ZoneOffset.UTC))
                it.setObject(4,UUID.fromString(user)); it.executeUpdate()
            }
            GeneratedCreatorInvite(code,expiry.toString())
        }
    }
    suspend fun report(user: String, from: String?, to: String?): AdReport = withContext(Dispatchers.IO) {
        DatabaseFactory.jdbcTransaction { c ->
            admin(c,user)
            val today = LocalDate.now(clock.withZone(zone))
            val start = from?.let(LocalDate::parse) ?: today
            val end = to?.let(LocalDate::parse) ?: today
            require(!end.isBefore(start) && !end.isAfter(today) && !start.isBefore(today.minusDays(89))) { "Choose up to 90 days" }
            val a = start.atStartOfDay(zone).toOffsetDateTime()
            val b = end.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
            fun query(sql: String, block: (java.sql.ResultSet)->Unit) {
                c.prepareStatement(sql).use { s -> s.setObject(1,a); s.setObject(2,b); s.executeQuery().use(block) }
            }
            val formats = mutableListOf<AdFormatCounts>()
            query("""SELECT format,count(*) FILTER(WHERE event='REQUEST'),count(*) FILTER(WHERE event='LOADED'),
                count(*) FILTER(WHERE event='IMPRESSION'),count(*) FILTER(WHERE event='CLICK'),
                count(*) FILTER(WHERE event IN ('FAILED','SHOW_FAILED')) FROM ad_sdk_events
                WHERE recorded_at>=? AND recorded_at<? GROUP BY format""") { r -> while(r.next()) formats.add(AdFormatCounts(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6))) }
            var devices = 0L
            query("SELECT count(DISTINCT device_hash) FROM ad_sdk_events WHERE recorded_at>=? AND recorded_at<? AND event='IMPRESSION'") { it.next(); devices=it.getLong(1) }
            val trend = mutableListOf<AdTrend>()
            val hour = start == end
            val trunc = if(hour) "hour" else "day"
            query("""SELECT date_trunc('$trunc',recorded_at AT TIME ZONE 'Asia/Kolkata') AS bucket,
                count(*) FILTER(WHERE format='INTERSTITIAL'),count(*) FILTER(WHERE format='BANNER')
                FROM ad_sdk_events WHERE recorded_at>=? AND recorded_at<? AND event='IMPRESSION' GROUP BY bucket ORDER BY bucket""") {
                r -> while(r.next()) trend.add(AdTrend(r.getTimestamp(1).toLocalDateTime().toString(),r.getLong(2),r.getLong(3)))
            }
            val recent = mutableListOf<AdRecent>()
            query("SELECT recorded_at,format,event FROM ad_sdk_events WHERE recorded_at>=? AND recorded_at<? ORDER BY recorded_at DESC LIMIT 20") {
                r -> while(r.next()) recent.add(AdRecent(r.getTimestamp(1).toInstant().toString(),r.getString(2),r.getString(3)))
            }
            // Cleanup is bounded to a report operation, not on the viewer's playback path.
            c.prepareStatement("DELETE FROM ad_sdk_events WHERE recorded_at<?").use {
                it.setObject(1,today.minusDays(90).atStartOfDay(zone).toOffsetDateTime()); it.executeUpdate()
            }
            AdReport(start.toString(),end.toString(),clock.instant().toString(),formats,devices,trend,recent,
                earnings=AdminRevenue.unavailable(clock,"AdMob revenue reporting not connected"))
        }
    }
}
