package com.example.vidoora

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.data.repository.UploadRepositoryImpl
import com.example.vidoora.core.database.AppDatabase
import com.example.vidoora.core.database.entity.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class QueueDeletionTest {
    private lateinit var db: AppDatabase
    private lateinit var client: HttpClient
    private lateinit var repo: UploadRepositoryImpl
    private lateinit var draft: File
    private val requests=mutableListOf<String>()
    private var active=false
    private var failure=false
    private var accepted=true
    private var completionRace=false
    @Before fun setup()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().build())
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        db.userDao().insertUser(UserEntity("alice","alice","fixture@example.test","Fixture",null,null,false,"USER","2026"))
        client=HttpClient(MockEngine { req ->
            val action=if(req.method==HttpMethod.Delete) "delete" else if(req.url.encodedPath.endsWith("complete-upload")) "complete" else "abort"
            requests+=action
            when {
                failure -> respond("{}",HttpStatusCode.ServiceUnavailable,headersOf(HttpHeaders.ContentType,"application/json"))
                action=="delete" && active -> respond("{}",HttpStatusCode.Conflict,headersOf(HttpHeaders.ContentType,"application/json"))
                action=="abort" && completionRace -> respond("{}",HttpStatusCode.Conflict,headersOf(HttpHeaders.ContentType,"application/json"))
                action=="complete" -> { active=false; respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) }
                action=="abort" -> { active=false; respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) }
                else -> respond("""{"success":true,"data":{"accepted":$accepted}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) }; defaultRequest { url("http://fixture.test/"); contentType(ContentType.Application.Json) } }
        repo=UploadRepositoryImpl(client,db.uploadTaskDao(),db.uploadPartDao(),db.userDao(),db,context)
        draft=File(File(context.filesDir,"upload_drafts").apply { mkdirs() },"deletion-fixture.draft").apply { writeText("fixture") }
        db.uploadTaskDao().insertTask(UploadTaskEntity("task","video","session",draft.absolutePath,7,"video/mp4","Fixture",null,"PRIVATE","DELETING",totalParts=1,userId="alice"))
        db.uploadPartDao().insertParts(listOf(UploadPartEntity("task",1,"https://fixture.test/part",0,6,7,"PENDING")))
    }
    @After fun close() { client.close(); db.close(); draft.delete() }
    @Test fun completedDeletionRemovesQueuePartsAndOnlyManagedDraft()=runBlocking {
        assertTrue(repo.performDeletion("task") is Resource.Success)
        assertNull(db.uploadTaskDao().getTaskById("task")); assertTrue(db.uploadPartDao().getPartsForTask("task").isEmpty())
        assertFalse(draft.exists()); assertEquals(listOf("delete"),requests)
    }
    @Test fun unfinishedUploadIsAbortedBeforeDeletionRetries()=runBlocking {
        active=true
        assertTrue(repo.performDeletion("task") is Resource.Success)
        assertEquals(listOf("delete","abort","delete"),requests)
    }
    @Test fun networkFailureRetainsDurableIntentAndDraftThenRetrySucceeds()=runBlocking {
        failure=true
        assertTrue(repo.performDeletion("task") is Resource.Error)
        assertEquals("DELETING",db.uploadTaskDao().getTaskById("task")!!.status); assertTrue(draft.exists())
        assertEquals("task",db.uploadTaskDao().getRecoverableTasks("alice").single().id)
        failure=false; assertTrue(repo.performDeletion("task") is Resource.Success)
    }
    @Test fun anotherAccountCannotDeleteOrRemoveQueueRecord()=runBlocking {
        db.userDao().clearUser(); db.userDao().insertUser(UserEntity("bob","bob","bob@example.test","Bob",null,null,false,"USER","2026"))
        assertTrue(repo.performDeletion("task") is Resource.Error)
        repo.removeDeletedVideo("video")
        assertTrue(requests.isEmpty()); assertNotNull(db.uploadTaskDao().getTaskById("task")); assertTrue(draft.exists())
    }
    @Test fun unacceptedServerResponseNeverRemovesDraftOrQueue()=runBlocking {
        accepted=false
        assertTrue(repo.performDeletion("task") is Resource.Error)
        assertNotNull(db.uploadTaskDao().getTaskById("task")); assertTrue(draft.exists())
    }
    @Test fun confirmedMyVideosDeletionRemovesMatchingQueueEntry()=runBlocking {
        repo.removeDeletedVideo("video")
        assertNull(db.uploadTaskDao().getTaskById("task")); assertFalse(draft.exists()); assertTrue(requests.isEmpty())
    }
    @Test fun lostCompletionResponseIsReconciledBeforeDeletion()=runBlocking {
        active=true;completionRace=true
        val part=db.uploadPartDao().getPartsForTask("task").single()
        db.uploadPartDao().updatePart(part.copy(status="SUCCESS",eTag="\"fixture-etag\""))
        assertTrue(repo.performDeletion("task") is Resource.Success)
        assertEquals(listOf("delete","abort","complete","delete"),requests)
    }
    @Test fun originalVideoOutsideManagedDraftFolderIsPreserved()=runBlocking {
        val original=File(draft.parentFile!!.parentFile,"original-fixture.mp4").apply { writeText("original") }
        try {
            val task=db.uploadTaskDao().getTaskById("task")!!
            db.uploadTaskDao().updateTask(task.copy(sourceUri=original.absolutePath))
            assertTrue(repo.performDeletion("task") is Resource.Success)
            assertTrue(original.exists())
        } finally { original.delete() }
    }
}
