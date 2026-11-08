package com.example.vidoora

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.vidoora.core.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RoomMigrationTest {
    @Test fun migrateV1PreservesUserAndCreatesValidatedUploadTables() = migrate(1)
    @Test fun migrateV2QuarantinesOwnerlessTasksAndPreservesParts() = migrate(2)

    private fun migrate(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE cached_users (id TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL, email TEXT NOT NULL, displayName TEXT NOT NULL, avatarUrl TEXT, bio TEXT, isSuspended INTEGER NOT NULL, role TEXT NOT NULL, createdAt TEXT NOT NULL)")
                    db.execSQL("INSERT INTO cached_users VALUES ('alice','alice','alice@example.test','Alice',NULL,NULL,0,'USER','2026-10-12')")
                    if (version == 2) {
                        AppDatabase.MIGRATION_1_2.migrate(db)
                        db.execSQL("INSERT INTO upload_tasks (id,videoId,uploadSessionId,sourceUri,fileSizeBytes,mimeType,title,visibility,status,totalParts,createdAt,updatedAt) VALUES ('task','video','session','/draft',10,'video/mp4','Title','PRIVATE','UPLOADING',1,1,1)")
                        db.execSQL("INSERT INTO upload_parts (taskId,partNumber,uploadUrl,startByte,endByte,sizeBytes,status) VALUES ('task',1,'https://storage.test',0,9,10,'PENDING')")
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        helper.writableDatabase
        helper.close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).build()
        try {
            assertEquals("alice", room.userDao().getUserOnce()?.id)
            if (version == 2) {
                val task = room.uploadTaskDao().getTaskById("task")!!
                assertEquals("", task.userId)
                assertEquals("FAILED", task.status)
                assertEquals(1, room.uploadPartDao().getPartsForTask("task").size)
                assertEquals(0, room.uploadTaskDao().getRecoverableTasks("alice").size)
            }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}
