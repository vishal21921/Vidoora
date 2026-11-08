package com.vidoora.backend

import org.flywaydb.core.Flyway
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID

class LinkOnlyMigrationIntegrationTest {
    @Test fun v4WithdrawsLegacyPublicAndPreservesExistingUnlistedShares() {
        assumeTrue("Explicit real PostgreSQL opt-in required",System.getenv("VIDOORA_POSTGRES_TEST")=="true")
        val url=checkNotNull(System.getenv("DATABASE_URL")); require(url.startsWith("jdbc:postgresql:"))
        val username=checkNotNull(System.getenv("DB_USERNAME")); val password=checkNotNull(System.getenv("DB_PASSWORD"))
        val schema="vidoora_privacy_test_"+UUID.randomUUID().toString().replace("-","")
        require(Regex("vidoora_privacy_test_[0-9a-f]{32}").matches(schema))
        fun connection()=DriverManager.getConnection(url,username,password).also { c -> c.createStatement().use { it.execute("SET search_path TO $schema") } }
        DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("3").load().migrate()
            val owner=UUID.randomUUID()
            connection().use { c -> c.prepareStatement("INSERT INTO users(id,username,email,password_hash,display_name) VALUES (?,'privacyfixture','privacy@example.test','not-a-login','Fixture')").use { it.setObject(1,owner); it.executeUpdate() } }
            val records=listOf("PUBLIC","UNLISTED","PRIVATE").associateWith { UUID.randomUUID() to UUID.randomUUID() }
            for((visibility,pair) in records) connection().use { c ->
                c.prepareStatement("INSERT INTO videos(id,uploader_id,title,visibility,publication_state,is_published,published_at,share_version,raw_object_key) VALUES (?,?,'Privacy fixture',?,'PROCESSED',?,CASE WHEN ? THEN NOW() ELSE NULL END,?,'raw/privacy-fixture.mp4')").use {
                    it.setObject(1,pair.first); it.setObject(2,owner); it.setString(3,visibility); it.setBoolean(4,visibility!="PRIVATE"); it.setBoolean(5,visibility!="PRIVATE"); it.setObject(6,pair.second); it.executeUpdate()
                }
            }
            Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
            for((visibility,pair) in records) connection().use { c ->
                c.prepareStatement("SELECT visibility,is_published,published_at,share_version,raw_object_key FROM videos WHERE id=?").use { s ->
                    s.setObject(1,pair.first); s.executeQuery().use { row ->
                        assertTrue(row.next()); assertEquals("raw/privacy-fixture.mp4",row.getString(5))
                        if(visibility=="PUBLIC") {
                            assertEquals("PRIVATE",row.getString(1)); assertFalse(row.getBoolean(2)); assertNull(row.getTimestamp(3)); assertNotEquals(pair.second,row.getObject(4,UUID::class.java))
                        } else {
                            assertEquals(visibility,row.getString(1)); assertEquals(visibility=="UNLISTED",row.getBoolean(2)); assertEquals(pair.second,row.getObject(4,UUID::class.java))
                            if(visibility=="UNLISTED") assertNotNull(row.getTimestamp(3))
                        }
                    }
                }
            }
            connection().use { c ->
                c.prepareStatement("INSERT INTO videos(uploader_id,title) VALUES (?,'Default privacy fixture') RETURNING visibility").use {
                    it.setObject(1,owner); it.executeQuery().use { row -> assertTrue(row.next()); assertEquals("PRIVATE",row.getString(1)) }
                }
                c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM videos").use { row -> assertTrue(row.next()); assertEquals(4,row.getInt(1)) } }
            }
        } finally {
            // Only the generated, validated schema owned by this test is removed.
            DriverManager.getConnection(url,username,password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
