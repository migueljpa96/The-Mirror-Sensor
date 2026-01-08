package com.mirror.sensor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UploadQueueEntity::class, SessionStateEntity::class],
    version = 2, // Bumped for Hardening
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // HARDENING: Schema Migration 1 -> 2
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // For MVP/Proto, we drop/recreate.
                // In Prod, we would use ALTER TABLE to add columns.
                database.execSQL("DROP TABLE IF EXISTS upload_queue")
                database.execSQL("DROP TABLE IF EXISTS session_state")
                // Tables recreated by Room automatically on next access check
                // (Note: In strict production, write explicit CREATE statements here)
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mirror_sensor_db"
                )
                    .fallbackToDestructiveMigration() // Allowed for Alpha
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}