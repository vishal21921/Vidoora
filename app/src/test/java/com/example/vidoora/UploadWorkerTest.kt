package com.example.vidoora

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.vidoora.core.data.repository.UploadRepositoryImpl
import com.example.vidoora.core.common.Resource
import com.example.vidoora.core.database.AppDatabase
import com.example.vidoora.core.database.entity.*
import com.example.vidoora.core.work.UploadWorker
import com.google.common.util.concurrent.Futures
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class UploadWorkerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var api: HttpClient
    private lateinit var storage: HttpClient
    private lateinit var repository: UploadRepositoryImpl
    private var storageStatus = HttpStatusCode.OK
    private var abortStatus = HttpStatusCode.OK
    private var omitEtag = false
    private var cancelDuringPut = false
    private var puts = 0
    private var completions = 0
    private var lastBytes = byteArrayOf()
    private lateinit var draft: File

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.userDao().insertUser(UserEntity("alice", "alice", "alice@example.test", "Alice", null, null, false, "USER", "2026-10-12"))
        api = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/refresh") -> """{"success":true,"data":{"videoId":"video","uploadSessionId":"session","refreshedParts":[{"partNumber":1,"uploadUrl":"https://storage.test/1"}]}}"""
                path.endsWith("/abort-upload") -> if (abortStatus == HttpStatusCode.OK) """{"success":true,"data":{"videoId":"video","publicationState":"CANCELLED","message":"cancelled"}}""" else """{"success":false,"error":{"code":"UNAVAILABLE","message":"retry"}}"""
                path.endsWith("/complete-upload") -> {
                    completions++
                    """{"success":true,"data":{"videoId":"video","publicationState":"UPLOADED","jobId":"job","message":"uploaded"}}"""
                }
                else -> error("Unexpected API request: $path")
            }
            respond(body, if (path.endsWith("/abort-upload")) abortStatus else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { url("http://backend.test/"); contentType(ContentType.Application.Json) }
        }
        storage = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            puts++
            lastBytes = (request.body as ByteArrayContent).bytes()
            if (cancelDuringPut) {
                db.uploadTaskDao().updateTaskStatus("task", "PAUSED")
                throw CancellationException("Worker stopped during provider PUT")
            }
            respond("", storageStatus, if (omitEtag) headersOf() else headersOf(HttpHeaders.ETag, "\"real-provider-tag\""))
        })
        repository = UploadRepositoryImpl(api, db.uploadTaskDao(), db.uploadPartDao(), db.userDao(), db, context)
        draft = File(File(context.filesDir, "upload_drafts").apply { mkdirs() }, "test.draft")
        draft.writeBytes(byteArrayOf(1, 2, 3, 4))
        db.uploadTaskDao().insertTask(UploadTaskEntity("task", "video", "session", draft.absolutePath, 4, "video/mp4", "Test", null, "PRIVATE", "QUEUED", totalParts = 1, userId = "alice"))
        db.uploadPartDao().insertParts(listOf(UploadPartEntity("task", 1, "https://storage.test/1", 0, 3, 4, "PENDING")))
    }
    @After fun tearDown() {
        if (::api.isInitialized) api.close()
        if (::storage.isInitialized) storage.close()
        if (::db.isInitialized) db.close()
        if (::draft.isInitialized) draft.delete()
    }
    private fun worker(attempt: Int = 0): UploadWorker = TestListenableWorkerBuilder<UploadWorker>(context)
        .setInputData(workDataOf("KEY_TASK_ID" to "task"))
        .setRunAttemptCount(attempt)
        .setForegroundUpdater { _, _, _ -> Futures.immediateFuture(null) }
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                UploadWorker(appContext, workerParameters, db.uploadTaskDao(), db.uploadPartDao(), db.userDao(), repository, storage)
        }).build()

    @Test fun successfulUploadPreservesBytesAndRealEtagAndCleansDraft() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), lastBytes)
        assertEquals("\"real-provider-tag\"", db.uploadPartDao().getPartsForTask("task").single().eTag)
        assertEquals("UPLOADED", db.uploadTaskDao().getTaskById("task")?.status)
        assertFalse(draft.exists())
    }
    @Test fun transientPutFailureRetriesThenResumes() = runBlocking {
        storageStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("RETRYING", db.uploadTaskDao().getTaskById("task")?.status)
        storageStatus = HttpStatusCode.OK
        assertEquals(ListenableWorker.Result.success(), worker(1).doWork())
        assertEquals(2, puts)
    }
    @Test fun completedPartSurvivesNewWorkerWithoutAnotherPut() = runBlocking {
        db.uploadPartDao().updatePart(db.uploadPartDao().getPartsForTask("task").single().copy(status = "SUCCESS", eTag = "\"saved-tag\""))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, puts)
        assertEquals(1, completions)
    }
    @Test fun pausedAndForeignAccountTasksDoNotSendBytes() = runBlocking {
        db.uploadTaskDao().updateTaskStatus("task", "PAUSED")
        worker().doWork()
        db.uploadTaskDao().updateTaskStatus("task", "QUEUED")
        db.userDao().clearUser()
        worker().doWork()
        assertEquals(0, puts)
        assertEquals(0, completions)
    }
    @Test fun missingEtagCannotComplete() = runBlocking {
        omitEtag = true
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertEquals(0, completions)
        assertEquals("FAILED", db.uploadTaskDao().getTaskById("task")?.status)
    }
    @Test fun cancellationRetainsDraftUntilBackendAcknowledges() = runBlocking {
        db.uploadTaskDao().updateTaskStatus("task", "CANCELLING")
        abortStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(draft.exists())
        assertEquals("CANCELLING", db.uploadTaskDao().getTaskById("task")?.status)
        abortStatus = HttpStatusCode.OK
        assertEquals(ListenableWorker.Result.success(), worker(1).doWork())
        assertEquals("CANCELLED", db.uploadTaskDao().getTaskById("task")?.status)
        assertFalse(draft.exists())
        assertEquals(0, puts)
    }
    @Test fun stoppingWorkerDoesNotOverwritePausedState() = runBlocking {
        cancelDuringPut = true
        try { worker().doWork(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals("PAUSED", db.uploadTaskDao().getTaskById("task")?.status)
        assertEquals(0, completions)
    }
    @Test fun retriesAreBoundedAndClientErrorsAreTerminal() = runBlocking {
        storageStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(ListenableWorker.Result.failure(), worker(6).doWork())
        db.uploadTaskDao().updateTaskStatus("task", "QUEUED")
        storageStatus = HttpStatusCode.BadRequest
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }
    @Test fun unavailableDraftStorageReturnsErrorWithoutSchedulingOrNetwork() = runBlocking {
        val blockedRoot = File(context.cacheDir, "blocked-draft-test").apply { mkdirs() }
        val blocker = File(blockedRoot, "upload_drafts").apply { writeText("not a directory") }
        val blockedContext = object : ContextWrapper(context) { override fun getFilesDir(): File = blockedRoot }
        val blockedRepository = UploadRepositoryImpl(api, db.uploadTaskDao(), db.uploadPartDao(), db.userDao(), db, blockedContext)
        try {
            val result = blockedRepository.initiateAndQueueUpload("file:///unread-source", "Test", null, "PRIVATE", 4, "video/mp4")
            assertTrue(result is Resource.Error)
            assertEquals("Cannot create upload draft directory", (result as Resource.Error).message)
            assertEquals(0, puts)
        } finally { blocker.delete(); blockedRoot.delete() }
    }
}
