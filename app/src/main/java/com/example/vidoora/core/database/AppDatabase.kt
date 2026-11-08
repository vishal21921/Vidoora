package com.example.vidoora.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vidoora.core.database.dao.UploadPartDao
import com.example.vidoora.core.database.dao.UploadTaskDao
import com.example.vidoora.core.database.dao.UserDao
import com.example.vidoora.core.database.entity.UploadPartEntity
import com.example.vidoora.core.database.entity.UploadTaskEntity
import com.example.vidoora.core.database.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        UploadTaskEntity::class,
        UploadPartEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun uploadPartDao(): UploadPartDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_users ADD COLUMN publicId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_tasks ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE upload_tasks SET status = 'FAILED', errorMessage = 'Legacy upload has no account owner. Select the video again.' WHERE status NOT IN ('UPLOADED', 'CANCELLED')")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS upload_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        videoId TEXT NOT NULL,
                        uploadSessionId TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        fileSizeBytes INTEGER NOT NULL,
                        mimeType TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT,
                        visibility TEXT NOT NULL,
                        status TEXT NOT NULL,
                        progressPercentage INTEGER NOT NULL DEFAULT 0,
                        totalParts INTEGER NOT NULL,
                        completedPartsCount INTEGER NOT NULL DEFAULT 0,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS upload_parts (
                        taskId TEXT NOT NULL,
                        partNumber INTEGER NOT NULL,
                        uploadUrl TEXT NOT NULL,
                        startByte INTEGER NOT NULL,
                        endByte INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        eTag TEXT,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (taskId, partNumber),
                        FOREIGN KEY (taskId) REFERENCES upload_tasks (id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS index_upload_parts_taskId ON upload_parts (taskId)")
            }
        }
    }
}
