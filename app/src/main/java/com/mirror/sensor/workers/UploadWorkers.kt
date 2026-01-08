package com.mirror.sensor.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mirror.sensor.data.UploadConfig
import com.mirror.sensor.data.db.AppDatabase

class ContextUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()

        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        // HARDENING: Only process items for the requested user
        // This prevents a logged-out user's worker from uploading previous user's data
        val pendingItems = dao.getItemsByStatus("PENDING").filter {
            it.user_id == targetUserId &&
                    (it.file_type == "PHYS_LOG" || it.file_type == "SCREEN_LOG")
        }

        if (pendingItems.isEmpty()) return Result.success()

        pendingItems.forEach { item ->
            dao.update(item.copy(status = "UPLOADING"))
            try {
                // STUB: Upload Logic
                // Use item.trace_id in headers

                dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            } catch (e: Exception) {
                val nextDelay = UploadConfig.getContextDelay(item.attempts + 1)
                dao.update(item.copy(
                    status = "PENDING",
                    attempts = item.attempts + 1,
                    retry_after = System.currentTimeMillis() + nextDelay
                ))
            }
        }
        return Result.success()
    }
}

class HeavyUploadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val targetUserId = inputData.getString("USER_ID") ?: return Result.failure()
        val dao = AppDatabase.getDatabase(applicationContext).uploadDao()

        val pendingItems = dao.getItemsByStatus("PENDING").filter {
            it.user_id == targetUserId && it.file_type == "AUDIO"
        }

        if (pendingItems.isEmpty()) return Result.success()

        pendingItems.forEach { item ->
            if (System.currentTimeMillis() < item.retry_after) return@forEach

            dao.update(item.copy(status = "UPLOADING"))
            try {
                // STUB: Upload Logic

                dao.update(item.copy(status = "COMPLETED", attempts = item.attempts + 1))
            } catch (e: Exception) {
                val nextDelay = UploadConfig.getHeavyDelay(item.attempts + 1)
                dao.update(item.copy(
                    status = "PENDING",
                    attempts = item.attempts + 1,
                    retry_after = System.currentTimeMillis() + nextDelay
                ))
            }
        }
        return Result.success()
    }
}