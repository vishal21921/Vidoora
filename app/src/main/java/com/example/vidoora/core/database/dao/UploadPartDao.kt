package com.example.vidoora.core.database.dao

import androidx.room.*
import com.example.vidoora.core.database.entity.UploadPartEntity

@Dao
interface UploadPartDao {
    @Query("SELECT * FROM upload_parts WHERE taskId = :taskId ORDER BY partNumber ASC")
    suspend fun getPartsForTask(taskId: String): List<UploadPartEntity>

    @Query("SELECT * FROM upload_parts WHERE taskId = :taskId AND status != 'SUCCESS' ORDER BY partNumber ASC")
    suspend fun getUnfinishedPartsForTask(taskId: String): List<UploadPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<UploadPartEntity>)

    @Update
    suspend fun updatePart(part: UploadPartEntity)

    @Query("UPDATE upload_parts SET uploadUrl = :newUrl WHERE taskId = :taskId AND partNumber = :partNumber")
    suspend fun updatePartUrl(taskId: String, partNumber: Int, newUrl: String)

    @Query("UPDATE upload_parts SET status = 'PENDING' WHERE taskId = :taskId AND status = 'FAILED'")
    suspend fun resetFailedParts(taskId: String)
}
