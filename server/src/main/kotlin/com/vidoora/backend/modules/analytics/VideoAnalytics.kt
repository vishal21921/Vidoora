package com.vidoora.backend.modules.analytics

import com.vidoora.backend.infrastructure.DatabaseFactory
import com.vidoora.backend.modules.playback.PlaybackDenied
import com.vidoora.backend.modules.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class AnalyticsEvent(val sessionId: String, val viewerKey: String, val playedMs: Long = 0,
    val started: Boolean = false, val ended: Boolean = false) {
    override fun toString() = "AnalyticsEvent(<redacted>)"
}
@Serializable data class AnalyticsAccepted(val accepted: Boolean = true)
@Serializable data class AnalyticsMetrics(val opens: Long, val uniqueViewers: Long, val starts: Long,
    val views: Long, val watchSeconds: Long, val completions: Long)
@Serializable data class AnalyticsDay(val day: String, val metrics: AnalyticsMetrics)
@Serializable data class AnalyticsVideo(val id: String, val title: String, val creatorId: String, val metrics: AnalyticsMetrics)
@Serializable data class AnalyticsCreator(val id: String, val metrics: AnalyticsMetrics)
@Serializable data class AnalyticsReport(val admin: Boolean, val from: String, val to: String,
    val totals: AnalyticsMetrics, val days: List<AnalyticsDay>, val videos: List<AnalyticsVideo>,
    val creators: List<AnalyticsCreator>, val timezone: String = "Asia/Kolkata")

@Serializable data class AnalyticsUploader(val id: String, val name: String, val email: String?,
    val active: Boolean, val videos: Long, val opens: Long, val views: Long, val watchSeconds: Long)
@Serializable data class AnalyticsCatalogVideo(val id: String, val title: String, val durationSeconds: Int,
    val thumbnail: Boolean, val shared: Boolean, val opens: Long, val views: Long, val watchSeconds: Long)
@Serializable data class AnalyticsCatalog<T>(val items: List<T>, val total: Long, val videoCount: Long = 0)
@Serializable data class UploadHealthEntry(val id: String, val title: String, val uploader: String,
    val uploaderId: String, val status: String, val elapsedSeconds: Long, val progress: Int?, val reason: String?)
@Serializable data class UploadHealthPage(val items: List<UploadHealthEntry>, val hasNext: Boolean)
@Serializable data class AdminPayment(val reference: String, val userId: String, val plan: String?,
    val status: String, val activation: String, val time: String, val expiry: String?, val updatedAt: String)
@Serializable data class AdminPayments(val day: Long, val week: Long, val month: Long,
    val items: List<AdminPayment>, val hasNext: Boolean, val earnings: AdminRevenue? = null)

class VideoAnalytics(private val playback: PlaybackService, secret: String,
    private val clock: Clock = Clock.systemUTC()) {
    val ads = AdAnalytics(secret, clock)
    val operations = OperationalHealth(clock)
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val lastCleanup = AtomicLong(0)
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun Connection.query(sql: String, vararg values: Any, block: (ResultSet) -> Unit) {
        prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use(block)
        }
    }
    private fun Connection.update(sql: String, vararg values: Any): Int = prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    private fun Connection.role(user: String, lock: Boolean = false): String? {
        var role: String? = null
        val suffix = if (lock) " FOR SHARE" else ""
        query("SELECT role FROM users WHERE id=? AND is_suspended=FALSE$suffix", UUID.fromString(user)) {
            if (it.next()) role = it.getString(1)
        }
        return role
    }

    private fun creatorNumber(publicId: String): Long = publicId.toLong().also { require(it > 10_000_000) } - 10_000_000
    private fun searchText(search: String?) = search.orEmpty().trim().take(128)
    private fun offset(page: Int) = Math.multiplyExact(page.also { require(it >= 0 && it <= 100_000) }, 30)

    suspend fun payments(user: String, days: Int, status: String, search: String?, page: Int): AdminPayments =
        withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            if (connection.role(user) != "ADMIN") throw PlaybackDenied()
            require(days in setOf(-2, -1, 0, 1, 7, 30, 90))
            require(status in setOf("ALL", "SUCCESSFUL", "PENDING", "FAILED", "REFUNDED", "EXPIRED", "REVIEW"))
            val now = clock.instant()
            var day = 0L
            var week = 0L
            var month = 0L
            connection.query("""
                SELECT p.plan_id,count(DISTINCT p.user_id) AS active
                FROM purchase_attempts p JOIN users u ON u.id=p.user_id
                LEFT JOIN play_token_bindings b ON b.token_hash=p.token_hash
                WHERE p.payment_state='SUCCESS' AND p.activation_state='ACTIVE' AND p.expires_at>?
                    AND u.is_suspended=FALSE AND coalesce(b.superseded,FALSE)=FALSE
                GROUP BY p.plan_id
            """, now.atOffset(ZoneOffset.UTC)) { rows ->
                while (rows.next()) when (rows.getString("plan_id")) {
                    "DAY" -> day = rows.getLong("active")
                    "WEEK" -> week = rows.getLong("active")
                    "MONTHLY" -> month = rows.getLong("active")
                }
            }
            val term = searchText(search)
            val cutoff = when(days) {
                0 -> Instant.EPOCH
                -2 -> now.atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant()
                -1 -> AdminReportingCycle.containing(now.atZone(zone).toLocalDate()).start.atStartOfDay(zone).toInstant()
                else -> now.atZone(zone).toLocalDate().minusDays(days.toLong()-1).atStartOfDay(zone).toInstant()
            }
            val until = now.atZone(zone).toLocalDate().plusDays(if(days == -2) 0 else 1)
                .atStartOfDay(zone).toInstant()
            val items = mutableListOf<AdminPayment>()
            connection.query("""
                WITH records AS (
                    SELECT p.id,p.user_id,p.plan_id,p.payment_state,p.activation_state,p.expires_at,p.created_at,p.updated_at
                    FROM purchase_attempts p WHERE NOT EXISTS (SELECT 1 FROM purchase_events e WHERE e.attempt_id=p.id)
                    UNION ALL
                    SELECT e.id,e.user_id,e.plan_id,
                        CASE WHEN e.period_hash=p.period_hash AND p.payment_state='REFUNDED' THEN 'REFUNDED' ELSE 'SUCCESS' END,
                        p.activation_state,e.expires_at,e.created_at,p.updated_at
                    FROM purchase_events e JOIN purchase_attempts p ON p.id=e.attempt_id
                ), states AS (
                    SELECT r.*,u.public_number,
                        CASE
                            WHEN payment_state='REFUNDED' THEN 'REFUNDED'
                            WHEN payment_state='FAILED' THEN 'FAILED'
                            WHEN payment_state='SUCCESS' AND expires_at<=? THEN 'EXPIRED'
                            WHEN payment_state='SUCCESS' THEN 'SUCCESSFUL'
                            WHEN payment_state='PENDING' OR activation_state='PENDING' THEN 'PENDING'
                            ELSE 'REVIEW'
                        END AS display_state
                    FROM records r JOIN users u ON u.id=r.user_id
                    WHERE r.created_at>=? AND r.created_at<? AND
                        (strpos((u.public_number+10000000)::text,?)>0 OR strpos(lower(r.id::text),lower(?))>0)
                )
                SELECT * FROM states WHERE (?='ALL' OR display_state=?)
                ORDER BY created_at DESC,id DESC LIMIT 31 OFFSET ?
            """, now.atOffset(ZoneOffset.UTC), cutoff.atOffset(ZoneOffset.UTC), until.atOffset(ZoneOffset.UTC), term, term,
                status, status, offset(page)) { rows ->
                while (rows.next()) items += AdminPayment(rows.getString("id"),
                    (rows.getLong("public_number") + 10_000_000).toString(), rows.getString("plan_id"),
                    rows.getString("display_state"), rows.getString("activation_state"),
                    rows.getTimestamp("created_at").toInstant().toString(),
                    rows.getTimestamp("expires_at")?.toInstant()?.toString(),
                    rows.getTimestamp("updated_at").toInstant().toString())
            }
            AdminPayments(day, week, month, items.take(30), items.size > 30,
                earnings=AdminRevenue.unavailable(clock,"Google Play financial reports not connected"))
        } }

    suspend fun uploadHealth(user: String, filter: String, page: Int): UploadHealthPage =
        withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            if (connection.role(user) != "ADMIN") throw PlaybackDenied()
            require(filter in setOf("ALL", "QUEUED", "PROCESSING", "STUCK", "FAILED"))
            val items = mutableListOf<UploadHealthEntry>()
            connection.query("""
                WITH health AS (
                    SELECT j.id, v.title, u.display_name, u.public_number, j.created_at,
                        CASE WHEN j.status='PROCESSING' AND (j.lease_until IS NULL OR j.lease_until <= now())
                             THEN 'STUCK' ELSE j.status END AS state,
                        greatest(0, extract(epoch FROM now() - j.created_at)::bigint) AS elapsed,
                        CASE WHEN j.status='PROCESSING' THEN j.progress_percent END AS progress,
                        CASE
                            WHEN j.status='PROCESSING' AND (j.lease_until IS NULL OR j.lease_until <= now()) THEN 'Processing worker lease expired'
                            WHEN j.error_message='Worker interrupted' THEN 'Worker interrupted'
                            WHEN j.error_message='Original unavailable or unsupported/invalid media' THEN 'Original unavailable or unsupported'
                            WHEN j.status='QUEUED' AND j.retry_count>0 THEN 'Waiting for automatic retry'
                            WHEN j.status='FAILED' THEN 'Processing attempts failed'
                        END AS reason
                    FROM processing_jobs j
                    JOIN videos v ON v.id=j.video_id
                    JOIN users u ON u.id=v.uploader_id
                    WHERE v.deleted_at IS NULL AND j.status IN ('QUEUED','PROCESSING','FAILED')
                )
                SELECT * FROM health WHERE (?='ALL' OR state=?)
                ORDER BY CASE state WHEN 'STUCK' THEN 0 WHEN 'FAILED' THEN 1 WHEN 'PROCESSING' THEN 2 ELSE 3 END,
                    created_at DESC,id LIMIT 31 OFFSET ?
            """, filter, filter, offset(page)) { rows ->
                while (rows.next()) {
                    items += UploadHealthEntry(rows.getString("id"), rows.getString("title"), rows.getString("display_name"),
                        (rows.getLong("public_number") + 10_000_000).toString(), rows.getString("state"),
                        rows.getLong("elapsed"), rows.getObject("progress", Int::class.javaObjectType), rows.getString("reason"))
                }
            }
            UploadHealthPage(items.take(30), items.size > 30)
        } }

    private val activity = """
        count(a.id) AS opens,
        count(DISTINCT CASE WHEN a.qualified THEN (a.video_id,a.viewer_hash) END) AS views,
        coalesce(sum(a.watched_ms),0)/1000 AS seconds
    """.trimIndent()

    suspend fun uploaders(user: String, from: String?, to: String?, search: String?, sort: String?,
        page: Int, includeEmail: Boolean): AnalyticsCatalog<AnalyticsUploader> {
        val (start, end) = range(from, to)
        val term = searchText(search)
        val order = when (sort) {
            "videos" -> "video_count DESC"
            "watch" -> "seconds DESC"
            "recent" -> "last_active DESC"
            "name" -> "lower(display_name)"
            else -> "views DESC, opens DESC"
        }
        return withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            if (connection.role(user) != "ADMIN") throw PlaybackDenied()
            val entries = mutableListOf<AnalyticsUploader>()
            var total = 0L
            var videos = 0L
            val emailColumn = if (includeEmail) "u.email" else "NULL::text"
            val roles = if (includeEmail) "u.role='CREATOR'" else
                "(u.role IN ('CREATOR','ADMIN') OR v.id IS NOT NULL OR EXISTS (SELECT 1 FROM creator_invites i WHERE i.redeemed_by=u.id))"
            connection.query("""
                WITH roster AS (
                    SELECT u.public_number,u.display_name,$emailColumn AS email,
                        u.role IN ('CREATOR','ADMIN') AND NOT u.is_suspended AS active,
                        count(DISTINCT v.id) AS video_count,$activity,
                        greatest(u.created_at,max(v.created_at),max(a.updated_at)) AS last_active
                    FROM users u
                    LEFT JOIN videos v ON v.uploader_id=u.id AND v.deleted_at IS NULL
                    LEFT JOIN video_analytics_sessions a ON a.video_id=v.id AND a.report_day BETWEEN ? AND ?
                    WHERE $roles AND
                        (strpos(lower(u.display_name),lower(?)) > 0 OR strpos((u.public_number+10000000)::text,?) > 0)
                    GROUP BY u.id
                )
                SELECT *,count(*) OVER() AS total,coalesce(sum(video_count) OVER(),0) AS all_videos
                FROM roster ORDER BY $order,public_number LIMIT 30 OFFSET ?
            """, start, end, term, term, offset(page)) { rows ->
                while (rows.next()) {
                    total = rows.getLong("total")
                    videos = rows.getLong("all_videos")
                    entries += AnalyticsUploader((rows.getLong("public_number") + 10_000_000).toString(),
                        rows.getString("display_name"), rows.getString("email"), rows.getBoolean("active"),
                        rows.getLong("video_count"), rows.getLong("opens"), rows.getLong("views"), rows.getLong("seconds"))
                }
            }
            AnalyticsCatalog(entries, total, videos)
        } }
    }

    suspend fun catalogVideos(user: String, creator: String?, from: String?, to: String?,
        search: String?, sort: String?, page: Int): AnalyticsCatalog<AnalyticsCatalogVideo> {
        val (start, end) = range(from, to)
        val term = searchText(search)
        val order = when (sort) {
            "watch" -> "seconds DESC"
            "recent", "all" -> "v.created_at DESC"
            else -> "views DESC, opens DESC"
        }
        return withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            val role = connection.role(user)
            if (role !in listOf("ADMIN", "CREATOR") || (creator != null && role != "ADMIN")) throw PlaybackDenied()
            val ownerColumn = if (creator == null) "u.id" else "u.public_number"
            val owner = if (creator == null) UUID.fromString(user) else creatorNumber(creator)
            val entries = mutableListOf<AnalyticsCatalogVideo>()
            var total = 0L
            connection.query("""
                SELECT v.id,v.title,v.duration_seconds,$activity,count(*) OVER() AS total,
                    v.publication_state='PROCESSED' AND v.thumbnail_object_key IS NOT NULL AS thumbnail,
                    v.publication_state='PROCESSED' AND v.visibility='UNLISTED' AND
                        v.is_published AND v.published_at IS NOT NULL AS shared
                FROM videos v JOIN users u ON u.id=v.uploader_id
                LEFT JOIN video_analytics_sessions a ON a.video_id=v.id AND a.report_day BETWEEN ? AND ?
                WHERE $ownerColumn=? AND v.deleted_at IS NULL AND
                    (strpos(lower(v.title),lower(?)) > 0 OR strpos(v.id::text,lower(?)) > 0)
                GROUP BY v.id ORDER BY $order,v.id LIMIT 30 OFFSET ?
            """, start, end, owner, term, term, offset(page)) { rows ->
                while (rows.next()) {
                    total = rows.getLong("total")
                    entries += AnalyticsCatalogVideo(rows.getString("id"), rows.getString("title"),
                        rows.getInt("duration_seconds"), rows.getBoolean("thumbnail"), rows.getBoolean("shared"),
                        rows.getLong("opens"), rows.getLong("views"), rows.getLong("seconds"))
                }
            }
            AnalyticsCatalog(entries, total)
        } }
    }

    private suspend fun adminVideoOwner(user: String, video: String): String = withContext(Dispatchers.IO) {
        DatabaseFactory.jdbcTransaction { connection ->
            if (connection.role(user) != "ADMIN") throw PlaybackDenied()
            var owner: String? = null
            connection.query("SELECT uploader_id FROM videos WHERE id=? AND deleted_at IS NULL", UUID.fromString(video)) {
                if (it.next()) owner = it.getString(1)
            }
            owner ?: throw com.vidoora.backend.modules.playback.PlaybackUnavailable()
        }
    }

    suspend fun adminThumbnail(user: String, video: String) = playback.thumbnail(adminVideoOwner(user, video), video)
    suspend fun adminShareLink(user: String, video: String) = playback.shareLink(adminVideoOwner(user, video), video)

    suspend fun removeUploader(user: String, publicId: String) = withContext(Dispatchers.IO) {
        DatabaseFactory.jdbcTransaction { connection ->
            if (connection.role(user, lock = true) != "ADMIN") throw PlaybackDenied()
            val changed = connection.update(
                "UPDATE users SET role='USER',updated_at=now() WHERE public_number=? AND role='CREATOR'",
                creatorNumber(publicId))
            if (changed == 0) throw com.vidoora.backend.modules.playback.PlaybackUnavailable()
        }
    }

    private fun range(fromText: String?, toText: String?): Pair<LocalDate, LocalDate> {
        val today = clock.instant().atZone(zone).toLocalDate()
        val to = toText?.let(LocalDate::parse) ?: today
        val from = fromText?.let(LocalDate::parse) ?: to.minusDays(6)
        require(!to.isAfter(today) && !from.isAfter(to) && !from.isBefore(today.minusDays(89))) {
            "Choose dates in the last 90 days"
        }
        return from to to
    }
    suspend fun event(user: String?, video: String, capability: String?, request: AnalyticsEvent) {
        require(request.playedMs in 0..21_600_000) { "Invalid watch counter" }
        val sessionId = UUID.fromString(request.sessionId)
        val videoId = UUID.fromString(video)
        val viewerKey = UUID.fromString(request.viewerKey)
        val details = playback.details(user, video, capability)
        if (!details.canPlay || details.isOwner) return
        val now = clock.instant()
        val day = now.atZone(zone).toLocalDate()
        withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            var creator: UUID? = null
            connection.query("SELECT uploader_id FROM videos WHERE id=? AND deleted_at IS NULL", videoId) {
                if (it.next()) creator = it.getObject(1, UUID::class.java)
            }
            val owner = creator ?: throw PlaybackDenied()
            val viewer = user ?: viewerKey.toString()
            val viewerHash = hash("analytics-v1|$day|${if (user == null) "guest" else "user"}|$viewer")
            var existing = false
            connection.query("SELECT 1 FROM video_analytics_sessions WHERE id=?", sessionId) { existing = it.next() }
            // A per-video daily ceiling bounds anonymous session storage. Counts are app-reported.
            if (!existing) connection.update("""INSERT INTO video_analytics_sessions
                (id,video_id,creator_id,viewer_hash,report_day,opened_at,updated_at,duration_ms)
                SELECT ?,?,?,?,?,?,?,? WHERE
                (SELECT count(*) FROM video_analytics_sessions WHERE video_id=? AND report_day=?) < 20000
                ON CONFLICT (id) DO NOTHING""", sessionId, videoId, owner, viewerHash, day,
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), details.durationSeconds.coerceAtLeast(1).toLong() * 1000,
                videoId, day)
            connection.query("SELECT * FROM video_analytics_sessions WHERE id=? FOR UPDATE", sessionId) { row ->
                if (!row.next()) return@query
                val opened = row.getTimestamp("opened_at").toInstant()
                val storedDay = row.getDate("report_day").toLocalDate()
                val expectedHash = hash("analytics-v1|$storedDay|${if (user == null) "guest" else "user"}|$viewer")
                if (row.getObject("video_id", UUID::class.java) != videoId || row.getString("viewer_hash") != expectedHash ||
                    now.isAfter(opened.plusSeconds(21600))) throw PlaybackDenied()
                val previousReport = row.getLong("reported_ms")
                if (request.playedMs < previousReport) return@query
                val duration = row.getLong("duration_ms")
                val sessionElapsed = Duration.between(opened, now).toMillis().coerceAtLeast(0)
                // Cumulative counters recover a missed heartbeat without losing its elapsed watch time.
                val watched = maxOf(row.getLong("watched_ms"),
                    request.playedMs.coerceAtMost(sessionElapsed + 1000).coerceAtMost(21_600_000))
                val started = row.getBoolean("started") || request.started
                val qualified = started && watched >= minOf(30000, (duration * 9 / 10).coerceAtLeast(1000))
                val completed = row.getBoolean("completed") || (request.ended && started && watched >= duration * 9 / 10)
                // Duplicate heartbeat bodies never add time or reset the elapsed-time bound.
                if (request.playedMs > previousReport || started != row.getBoolean("started") || completed != row.getBoolean("completed")) {
                    connection.update("""UPDATE video_analytics_sessions SET reported_ms=?,watched_ms=?,started=?,
                        qualified=?,completed=?,updated_at=? WHERE id=?""", request.playedMs, watched, started,
                        qualified, completed, now.atOffset(ZoneOffset.UTC), sessionId)
                }
            }
            if (now.epochSecond - lastCleanup.get() > 3600 && lastCleanup.getAndSet(now.epochSecond) < now.epochSecond - 3600) {
                connection.update("DELETE FROM video_analytics_sessions WHERE id IN (SELECT id FROM video_analytics_sessions WHERE opened_at < ? LIMIT 5000)",
                    now.minusSeconds(90L * 86400).atOffset(ZoneOffset.UTC))
            }
        } }
    }

    suspend fun report(user: String, fromText: String?, toText: String?, creatorText: String?, videoText: String?): AnalyticsReport {
        val (from, to) = range(fromText, toText)
        val video = videoText?.takeIf(String::isNotBlank)?.let(UUID::fromString)
        val creatorNumber = creatorText?.takeIf(String::isNotBlank)?.toLong()?.minus(10_000_000)
        return withContext(Dispatchers.IO) { DatabaseFactory.jdbcTransaction { connection ->
            val userId = UUID.fromString(user)
            val role = connection.role(user)
            if (role !in listOf("ADMIN", "CREATOR")) throw PlaybackDenied()
            val admin = role == "ADMIN"
            val args = mutableListOf<Any>(from, to)
            var where = "a.report_day BETWEEN ? AND ?"
            if (!admin) { where += " AND a.creator_id=?"; args += userId }
            if (creatorNumber != null) { where += " AND u.public_number=?"; args += creatorNumber }
            if (video != null) { where += " AND a.video_id=?"; args += video }
            val base = " FROM video_analytics_sessions a JOIN users u ON u.id=a.creator_id JOIN videos v ON v.id=a.video_id WHERE $where"
            val columns = """count(*) AS opens,count(DISTINCT a.viewer_hash) AS viewers,
                sum(CASE WHEN a.started THEN 1 ELSE 0 END) AS starts,
                count(DISTINCT CASE WHEN a.qualified THEN concat(a.video_id,a.viewer_hash) ELSE NULL END) AS views,
                coalesce(sum(a.watched_ms),0)/1000 AS seconds,
                count(DISTINCT CASE WHEN a.completed THEN concat(a.video_id,a.viewer_hash) ELSE NULL END) AS completions"""
            fun metrics(row: ResultSet) = AnalyticsMetrics(row.getLong("opens"), row.getLong("viewers"), row.getLong("starts"),
                row.getLong("views"), row.getLong("seconds"), row.getLong("completions"))
            var totals = AnalyticsMetrics(0,0,0,0,0,0)
            connection.query("SELECT $columns$base", *args.toTypedArray()) { it.next(); totals = metrics(it) }
            val days = mutableListOf<AnalyticsDay>()
            connection.query("SELECT a.report_day,$columns$base GROUP BY a.report_day ORDER BY a.report_day", *args.toTypedArray()) {
                while (it.next()) days += AnalyticsDay(it.getDate("report_day").toString(), metrics(it))
            }
            val videos = mutableListOf<AnalyticsVideo>()
            connection.query("SELECT a.video_id,v.title,u.public_number,$columns$base GROUP BY a.video_id,v.title,u.public_number ORDER BY views DESC,opens DESC,a.video_id LIMIT 100", *args.toTypedArray()) {
                while (it.next()) videos += AnalyticsVideo(it.getString("video_id"), it.getString("title"),
                    (it.getLong("public_number") + 10_000_000).toString(), metrics(it))
            }
            val creators = mutableListOf<AnalyticsCreator>()
            if (admin) connection.query("SELECT u.public_number,$columns$base GROUP BY u.public_number ORDER BY views DESC,opens DESC,u.public_number LIMIT 100", *args.toTypedArray()) {
                while (it.next()) creators += AnalyticsCreator((it.getLong("public_number") + 10_000_000).toString(), metrics(it))
            }
            AnalyticsReport(admin, from.toString(), to.toString(), totals, days, videos, creators)
        } }
    }
}
