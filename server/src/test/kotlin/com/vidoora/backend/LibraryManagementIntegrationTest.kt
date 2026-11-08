package com.vidoora.backend

import com.vidoora.backend.infrastructure.S3StorageService
import com.vidoora.backend.modules.auth.JwtConfig
import com.vidoora.backend.modules.playback.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class LibraryManagementIntegrationTest {
    @Test fun postgresLibrarySearchPaginationAndOwnerEditingPreservePrivacy()=runBlocking {
        assumeTrue("Explicit real PostgreSQL opt-in required",System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=checkNotNull(System.getenv("DATABASE_URL")); require(url.startsWith("jdbc:postgresql:"))
        val username=checkNotNull(System.getenv("DB_USERNAME")); val password=checkNotNull(System.getenv("DB_PASSWORD"))
        val schema="vidoora_library_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_library_test_[0-9a-f]{32}").matches(schema))
        fun connection()=DriverManager.getConnection(url,username,password).also { c -> c.createStatement().use { it.execute("SET search_path TO $schema") } }
        DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        val previousDb=TransactionManager.defaultDatabase
        try {
            Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            val owner=UUID.randomUUID(); val viewer=UUID.randomUUID(); val ids=(0..65).map { UUID.randomUUID() }
            connection().use { c ->
                for((who,name) in listOf(owner to "owner",viewer to "viewer")) c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,?,?,'not-a-login',?)").use {
                    it.setObject(1,who); it.setString(2,name); it.setString(3,"$name@example.test"); it.setString(4,name); it.executeUpdate()
                }
                ids.forEachIndexed { index,uuid ->
                    c.prepareStatement("INSERT INTO videos(id,uploader_id,title,description,created_at,publication_state,visibility,is_published,published_at,processed_bucket,master_playlist_key) VALUES (?,?,?,?,?,'PROCESSED','UNLISTED',TRUE,NOW(),'private-fixture',?)").use {
                        it.setObject(1,uuid); it.setObject(2,if(index<=60) owner else viewer); it.setString(3,"Fixture $index")
                        it.setString(4,if(index==0) "Literal 100%_! NEEDLE" else "Other")
                        it.setTimestamp(5,Timestamp.from(Instant.parse("2026-10-01T00:00:00.123456Z").minusSeconds((index/6).toLong())))
                        it.setString(6,"processed/$uuid/${UUID.randomUUID()}/master.m3u8"); it.executeUpdate()
                    }
                }
            }
            val db=Database.connect(url+(if(url.contains('?')) "&" else "?")+"currentSchema=$schema",driver="org.postgresql.Driver",user=username,password=password)
            TransactionManager.defaultDatabase=previousDb
            val config=MapApplicationConfig("r2.mockMode" to "true","jwt.secret" to "library-fixture-secret-at-least-32-characters")
            S3StorageService(config).use { store ->
                val service=PlaybackService(store,db,VideoShareTokens(JwtConfig(config)))
                val found=mutableListOf<String>(); var cursor: String?=null
                do {
                    val page=service.libraryPage(owner.toString(),cursor=cursor,limit=17)
                    found.addAll(page.items.map { it.id }); cursor=page.nextCursor
                } while(cursor!=null)
                val oldest=mutableListOf<String>(); cursor=null
                do { val page=service.libraryPage(owner.toString(),cursor=cursor,limit=17,sort="OLDEST"); oldest.addAll(page.items.map { it.id }); cursor=page.nextCursor } while(cursor!=null)
                assertEquals(found.reversed(),oldest)
                assertEquals(61,found.size); assertEquals(ids.take(61).map { it.toString() }.toSet(),found.toSet())
                assertEquals(5,service.libraryPage(viewer.toString()).items.size)
                assertEquals(listOf(ids[0].toString()),service.libraryPage(owner.toString(),"%_!").items.map { it.id })
                assertEquals(listOf(ids[0].toString()),service.libraryPage(owner.toString(),"needle").items.map { it.id })
                assertTrue(service.libraryPage(viewer.toString(),"needle").items.isEmpty())
                val capability=service.shareLink(owner.toString(),ids[0].toString()).url.substringAfter('#')
                try { service.editDetails(viewer.toString(),ids[0].toString(),EditVideoRequest("Stolen")); fail("Foreign edit accepted") } catch (_: PlaybackDenied) { }
                val result=service.editDetails(owner.toString(),ids[0].toString(),EditVideoRequest("Renamed","Updated description"))
                assertEquals("Renamed",result.title); assertEquals("Updated description",result.description)
                assertEquals("Renamed",service.details(viewer.toString(),ids[0].toString(),capability).title)
                assertTrue(service.libraryPage(owner.toString(),"needle").items.isEmpty())
                assertEquals(listOf(ids[0].toString()),service.libraryPage(owner.toString(),"renamed").items.map { it.id })
                connection().use { c -> c.createStatement().use { stmt ->
                    stmt.executeUpdate("UPDATE videos SET is_published=FALSE WHERE id IN ('${ids[1]}','${ids[2]}','${ids[3]}','${ids[4]}','${ids[5]}')")
                    stmt.executeUpdate("UPDATE videos SET publication_state='UPLOADED' WHERE id='${ids[3]}'")
                    stmt.executeUpdate("UPDATE videos SET publication_state='PROCESSING' WHERE id='${ids[4]}'")
                    stmt.executeUpdate("UPDATE videos SET publication_state='PROCESSING_FAILED' WHERE id='${ids[5]}'")
                    stmt.executeUpdate("UPDATE videos SET deleted_at=NOW() WHERE id='${ids[6]}'")
                } }
                assertEquals(setOf(ids[3].toString(),ids[4].toString()),service.libraryPage(owner.toString(),state="PROCESSING").items.map { it.id }.toSet())
                assertEquals(listOf(ids[5].toString()),service.libraryPage(owner.toString(),state="FAILED").items.map { it.id })
                val first=service.libraryPage(owner.toString(),"fixture",limit=1,sort="OLDEST",state="READY",sharing="PRIVATE")
                val second=service.libraryPage(owner.toString(),"fixture",cursor=first.nextCursor,limit=1,sort="OLDEST",state="READY",sharing="PRIVATE")
                assertEquals(setOf(ids[1].toString(),ids[2].toString()),(first.items+second.items).map { it.id }.toSet())
                assertNull(second.nextCursor)
                assertTrue(service.libraryPage(viewer.toString(),state="FAILED").items.isEmpty())
                val snapshots=service.libraryStatuses(owner.toString(),OwnerVideoStatusRequest(listOf(ids[3].toString(),ids[4].toString(),ids[6].toString(),ids[65].toString()),state="PROCESSING",sharing="PRIVATE"))
                assertEquals(setOf(ids[3].toString(),ids[4].toString()),snapshots.map { it.id }.toSet())
                assertTrue(service.libraryStatuses(viewer.toString(),OwnerVideoStatusRequest(listOf(ids[3].toString(),ids[4].toString()))).isEmpty())
                val folder=service.saveFolder(owner.toString(),null,FolderNameRequest("Private folder"))
                service.moveToFolder(owner.toString(),ids[0].toString(),folder.id)
                service.moveToFolder(owner.toString(),ids[1].toString(),folder.id)
                assertEquals(2,service.libraryPage(owner.toString(),folder=folder.id).items.size)
                assertNull(service.details(viewer.toString(),ids[0].toString(),capability).folderId)
                val foreign=service.saveFolder(viewer.toString(),null,FolderNameRequest("Other folder"))
                try { service.moveToFolder(owner.toString(),ids[0].toString(),foreign.id); fail("Foreign folder accepted") } catch (_: PlaybackDenied) { }
                connection().use { c ->
                    try { c.prepareStatement("UPDATE videos SET folder_id=? WHERE id=?").use { it.setObject(1,UUID.fromString(foreign.id)); it.setObject(2,ids[0]); it.executeUpdate() }; fail("Cross-owner folder FK accepted") }
                    catch(e: java.sql.SQLException) { assertEquals("23503",e.sqlState) }
                }
                service.saveFolder(owner.toString(),folder.id,FolderNameRequest("Renamed folder"))
                service.removeFolder(owner.toString(),folder.id)
                assertNull(service.details(owner.toString(),ids[0].toString()).folderId)
                assertTrue(service.details(viewer.toString(),ids[0].toString(),capability).canPlay)
                val guest=service.details(null,ids[0].toString(),capability)
                assertTrue(guest.canPlay); assertFalse(guest.isOwner); assertNull(guest.folderId)
                try { service.details(null,ids[0].toString()); fail("Guest ID-only access accepted") } catch (_: PlaybackDenied) { }
                try { service.details(null,ids[1].toString(),capability); fail("Guest cross-video access accepted") } catch (_: PlaybackDenied) { }
                service.unpublish(owner.toString(),ids[0].toString())
                try { service.details(null,ids[0].toString(),capability); fail("Revoked guest capability accepted") } catch (_: PlaybackDenied) { }
            }
            connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname='$schema' AND indexname='idx_videos_owner_created_id'").use { r -> assertTrue(r.next()); assertEquals(1,r.getInt(1)) } } }
        } finally {
            TransactionManager.defaultDatabase=previousDb
            // Only the generated and validated schema belonging to this test is removed.
            DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}




