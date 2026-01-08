package com.mirror.sensor.data

object UploadConfig {
    // Feature Flag
    const val SESSION_CHUNKING_ENABLED = true

    // --- CONTEXT LANE (Logs) ---
    const val TAG_CONTEXT = "upload_context"
    const val CONTEXT_MAX_ATTEMPTS = 10
    const val CONTEXT_INITIAL_DELAY_MS = 30_000L // 30s

    fun getContextJitter() = (-5000..5000).random().toLong()

    fun getContextDelay(attempt: Int): Long {
        return (attempt * CONTEXT_INITIAL_DELAY_MS) + getContextJitter()
    }

    // --- HEAVY LANE (Audio) ---
    const val TAG_HEAVY = "upload_heavy"
    const val HEAVY_MAX_ATTEMPTS = 20
    const val HEAVY_INITIAL_DELAY_MS = 60_000L // 1m
    const val HEAVY_MAX_DELAY_CAP_MS = 14_400_000L // 4 hours

    fun getHeavyJitter() = (-10000..10000).random().toLong()

    fun getHeavyDelay(attempt: Int): Long {
        val base = HEAVY_INITIAL_DELAY_MS * (1 shl (attempt - 1)) // 2^(n-1)
        return (base.coerceAtMost(HEAVY_MAX_DELAY_CAP_MS)) + getHeavyJitter()
    }

    // Shard settings
    const val SHARD_DURATION_MS = 10 * 60 * 1000L // 10 minutes
}