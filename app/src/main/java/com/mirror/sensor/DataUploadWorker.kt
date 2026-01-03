package com.mirror.sensor

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

class DataUploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val storage = FirebaseStorage.getInstance()

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting Data Upload...")
        val filesDir = applicationContext.getExternalFilesDir(null) ?: return Result.failure()

        // 1. Find valid files (Ignore temp files)
        val filesToUpload = filesDir.listFiles { file ->
            val isTemp = file.name.startsWith("temp_")
            val isValidType = file.name.endsWith(".m4a") || file.name.endsWith(".jsonl")

            !isTemp && isValidType && file.length() > 0
        }

        if (filesToUpload.isNullOrEmpty()) {
            return Result.success()
        }

        for (file in filesToUpload) {
            try {
                // Determine folder - STRICT MATCHING
                val cloudFolder = when {
                    file.name.endsWith(".m4a") -> "audio_raw"
                    file.name.contains("PHYSICAL") -> "physical_logs"
                    file.name.contains("NOTIFS") -> "notification_logs"
                    file.name.contains("SCREEN") -> "screen_logs" // Explicit check!
                    else -> "unknown_logs" // Catch-all for safety
                }

                val cloudPath = "$cloudFolder/${file.name}"
                val storageRef = storage.reference.child(cloudPath)
                val fileUri = Uri.fromFile(file)

                Log.d(TAG, "Uploading to [$cloudFolder]: ${file.name}")
                storageRef.putFile(fileUri).await()

                // Delete after upload
                if (file.delete()) {
                    Log.d(TAG, "Deleted local: ${file.name}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload Error: ${file.name}", e)
                // Continue to next file
            }
        }
        return Result.success()
    }

    companion object {
        const val TAG = "TheMirrorUpload"
    }
}