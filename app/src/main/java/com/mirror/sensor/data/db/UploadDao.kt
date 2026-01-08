package com.mirror.sensor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface UploadDao {
    // --- Queue Operations ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQueueItem(item: UploadQueueEntity): Long

    @Query("SELECT * FROM upload_queue WHERE status = :status ORDER BY created_at ASC")
    suspend fun getItemsByStatus(status: String): List<UploadQueueEntity>

    @Query("UPDATE upload_queue SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetStuckUploads(): Int

    @Update
    suspend fun update(item: UploadQueueEntity)

    // --- Session State Operations ---
    @Query("SELECT * FROM session_state WHERE id = 1")
    suspend fun getActiveSession(): SessionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSessionState(state: SessionStateEntity)

    @Query("DELETE FROM session_state")
    suspend fun clearSessionState()
}