package com.simplesync.companion.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderConfig::class, TrackedFile::class, UploadJob::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderConfigDao(): FolderConfigDao
    abstract fun trackedFileDao(): TrackedFileDao
    abstract fun uploadJobDao(): UploadJobDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Migration 1 → 2: add sha256Hash column to tracked_files
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tracked_files ADD COLUMN sha256Hash TEXT")
            }
        }

        // Migration 2 → 3: add uploadHiddenFiles to folder_configs,
        //                   add uploadSpeedBps to upload_jobs
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE folder_configs ADD COLUMN uploadHiddenFiles INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN uploadSpeedBps INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "simplesynccompanion.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter fun fromJobStatus(v: JobStatus): String = v.name
    @TypeConverter fun toJobStatus(v: String): JobStatus = JobStatus.valueOf(v)
}
