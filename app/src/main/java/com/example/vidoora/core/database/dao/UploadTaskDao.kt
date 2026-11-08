package com.example.vidoora.core.database.dao

import androidx.room.*
import com.example.vidoora.core.database.entity.UploadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {
    @Query("SELECT * FROM upload_tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE status IN ('QUEUED', 'UPLOADING', 'RETRYING')")
    suspend fun getPendingTasks(): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks WHERE userId = :userId AND status IN ('QUEUED', 'UPLOADING', 'RETRYING', 'COMPLETING', 'CANCELLING', 'DELETING')")
    suspend fun getRecoverableTasks(userId: String): List<UploadTaskEntity>

    @Query("UPDATE upload_tasks SET status = :next, errorMessage = :error, updatedAt = :time WHERE id = :taskId AND status IN (:allowed)")
    suspend fun transition(taskId: String, allowed: List<String>, next: String, error: String? = null, time: Long = System.currentTimeMillis()): Int

    @Query("SELECT sourceUri FROM upload_tasks WHERE status NOT IN ('UPLOADED', 'CANCELLED')")
    suspend fun retainedDraftPaths(): List<String>

    @Query("SELECT * FROM upload_tasks")
    suspend fun getAllTasksOnce(): List<UploadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: UploadTaskEntity)

    @Update
    suspend fun updateTask(task: UploadTaskEntity)

    @Query("UPDATE upload_tasks SET status = :status, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE upload_tasks SET progressPercentage = :progress, completedPartsCount = :completedParts, updatedAt = :updatedAt WHERE id = :taskId")
    suspend fun updateProgress(taskId: String, progress: Int, completedParts: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM upload_tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: String)
}
