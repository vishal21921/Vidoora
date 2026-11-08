package com.vidoora.backend

import com.vidoora.backend.modules.auth.*
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.DriverManager
import java.util.UUID
import java.security.MessageDigest

class CreatorPostgresIntegrationTest {
 @Test fun realMigrationAndConcurrentSingleUseInvitation()=runBlocking {
    assumeTrue(System.getenv("VIDOORA_POSTGRES_TEST")=="true")
    val url=checkNotNull(System.getenv("DATABASE_URL"));require(url.startsWith("jdbc:postgresql:"))
    val user=checkNotNull(System.getenv("DB_USERNAME"));val password=checkNotNull(System.getenv("DB_PASSWORD"))
    val schema="vidoora_creator_test_"+UUID.randomUUID().toString().replace("-","")
    DriverManager.getConnection(url,user,password).use {c->c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
    try {
        Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("13").load().migrate()
        val legacyOwner=UUID.randomUUID();val legacyViewer=UUID.randomUUID();val legacyVideo=UUID.randomUUID()
        DriverManager.getConnection(url,user,password).use {c->c.createStatement().use {
            it.execute("INSERT INTO $schema.users(id,username,email,password_hash,display_name) VALUES ('$legacyOwner','legacy_creator','legacycreator@example.test','fixture','Legacy'),('$legacyViewer','legacy_viewer','legacyviewer@example.test','fixture','Legacy viewer')")
            it.execute("INSERT INTO $schema.videos(id,uploader_id,title) VALUES ('$legacyVideo','$legacyOwner','Preserved fixture')")
        }}
        Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        DriverManager.getConnection(url,user,password).use {c->c.createStatement().use {statement->
            statement.executeQuery("SELECT role,public_number FROM $schema.users WHERE id='$legacyOwner'").use {assertTrue(it.next());assertEquals("CREATOR",it.getString(1));assertTrue(it.getLong(2)>0)}
            statement.executeQuery("SELECT role FROM $schema.users WHERE id='$legacyViewer'").use {assertTrue(it.next());assertEquals("USER",it.getString(1))}
            statement.executeQuery("SELECT count(*) FROM $schema.videos WHERE id='$legacyVideo'").use {it.next();assertEquals(1,it.getInt(1))}
        }}
        val previous=org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase
        val db=Database.connect(url+(if(url.contains('?')) "&" else "?")+"currentSchema=$schema",driver="org.postgresql.Driver",user=user,password=password)
        org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase=previous
        val auth=AuthService(JwtConfig(MapApplicationConfig("jwt.secret" to "postgres-creator-fixture-secret-at-least-32-characters")),db)
        val first=auth.register(RegisterRequest(email="first@example.test",password="Password123!"))
        val second=auth.register(RegisterRequest(email="second@example.test",password="Password123!"))
        assertNotEquals(first.user.publicId,second.user.publicId)
        val code="strong-generated-fixture-invitation-123456"
        val hash=MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString(""){"%02x".format(it)}
        DriverManager.getConnection(url,user,password).use {c->c.createStatement().use {it.execute("INSERT INTO $schema.creator_invites(token_hash,expires_at) VALUES ('$hash',NOW()+INTERVAL '1 day')")}}
        val results=listOf(first,second).map {account->async(Dispatchers.IO){runCatching {auth.redeemCreatorInvite(account.user.id,code)}}}.awaitAll()
        assertEquals(1,results.count {it.isSuccess})
        assertEquals("CREATOR",results.single {it.isSuccess}.getOrThrow().role)
        val loser=if(results[0].isFailure) first else second
        assertEquals("USER",auth.getUserProfile(loser.user.id).role)
    } finally {
        DriverManager.getConnection(url,user,password).use {c->c.createStatement().use {it.execute("DROP SCHEMA $schema CASCADE")}}
    }
 }
}
