package com.mirror.sensor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueItem(item: UploadQueueEntity): Long

    // MODIFIED: Added LIMIT to prevent OOM on large backlogs
    @Query("SELECT * FROM upload_queue WHERE status = :status ORDER BY created_at ASC LIMIT :limit")
    suspend fun getItemsByStatus(status: String, limit: Int = 10): List<UploadQueueEntity>

    @Update
    suspend fun update(item: UploadQueueEntity)

    @Query("SELECT * FROM session_state WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveSession(): SessionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSessionState(state: SessionStateEntity)

    @Query("DELETE FROM session_state")
    suspend fun clearSessionState()

    // Crash Recovery: Reset 'UPLOADING' items back to 'PENDING' on app start
    @Query("UPDATE upload_queue SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetStuckUploads(): Int
}