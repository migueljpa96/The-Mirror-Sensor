package com.mirror.sensor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["status", "retry_after"]),
        // HARDENING: Add user_id to unique constraint to support multi-user switching
        Index(value = ["user_id", "session_id", "seq_index", "file_type"], unique = true)
    ]
)
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val user_id: String,       // HARDENING: Identity Bind
    val session_id: String,
    val seq_index: Int,
    val trace_id: String,      // HARDENING: Observability (UUID)
    val file_path: String,
    val file_type: String,     // PHYS_LOG, SCREEN_LOG, AUDIO
    val status: String = "PENDING",
    val attempts: Int = 0,
    val created_at: Long = System.currentTimeMillis(),
    val retry_after: Long = 0
)

@Entity(tableName = "session_state")
data class SessionStateEntity(
    @PrimaryKey val id: Int = 1,
    val user_id: String,       // HARDENING: Bind active session to specific user
    val session_id: String,
    val start_ts: Long,
    val last_seq_index: Int,
    val is_active: Boolean
)