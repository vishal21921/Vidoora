package com.vidoora.backend.infrastructure

import com.vidoora.backend.modules.user.UsersTable
import com.vidoora.backend.modules.playback.ReportsTable
import com.vidoora.backend.modules.playback.UserBlocksTable
import com.vidoora.backend.modules.video.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null
    @Synchronized
    fun init(config: ApplicationConfig) {
        if (dataSource != null) return
        val driver = config.propertyOrNull("database.driverClassName")?.getString() ?: "org.postgresql.Driver"
        val testMode = config.propertyOrNull("database.allowInMemoryFallback")?.getString()?.toBoolean() == true
        require(driver != "org.h2.Driver" || testMode) { "H2 requires explicit test mode" }
        val pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.property("database.jdbcUrl").getString()
            driverClassName = driver
            username = config.property("database.username").getString()
            password = config.property("database.password").getString()
            maximumPoolSize = config.propertyOrNull("database.maximumPoolSize")?.getString()?.toInt() ?: 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        })
        try {
            if (driver == "org.h2.Driver") {
                Database.connect(pool)
                transaction { SchemaUtils.create(com.vidoora.backend.modules.auth.PasswordResets, com.vidoora.backend.modules.auth.CreatorInvites, com.vidoora.backend.modules.downloads.PrivateDownloadClients, com.vidoora.backend.modules.downloads.PrivateDownloadRows, com.vidoora.backend.modules.downloads.PrivateDownloadAssets, com.vidoora.backend.modules.membership.PlayTokenBindings, com.vidoora.backend.modules.membership.PurchaseAttempts, com.vidoora.backend.modules.membership.PurchaseEvents, com.vidoora.backend.modules.membership.PlayPurchasesTable, UsersTable, com.vidoora.backend.modules.auth.RefreshSessionsTable, com.vidoora.backend.modules.playback.VideoFoldersTable, VideosTable, UploadSessionsTable, ProcessingJobsTable, ReportsTable, UserBlocksTable) }
            } else {
                Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
                Database.connect(pool)
            }
            dataSource = pool
        } catch (e: Exception) { pool.close(); throw e }
    }
    @Synchronized fun close() { dataSource?.close(); dataSource = null }
    /** Processor transactions use PostgreSQL locking; never replace them with H2 semantics. */
    fun <T> jdbcTransaction(block: (java.sql.Connection) -> T): T {
        val pool = checkNotNull(dataSource) { "Database is not initialized" }
        return pool.connection.use { connection ->
            try { block(connection).also { connection.commit() } }
            catch (e: Throwable) { connection.rollback(); throw e }
        }
    }
    suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
}



