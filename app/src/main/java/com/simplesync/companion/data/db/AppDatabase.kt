package com.simplesync.companion.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderConfig::class, TrackedFile::class, UploadJob::class, WaFileCache::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderConfigDao(): FolderConfigDao
    abstract fun trackedFileDao(): TrackedFileDao
    abstract fun uploadJobDao(): UploadJobDao
    abstract fun waFileCacheDao(): WaFileCacheDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tracked_files ADD COLUMN sha256Hash TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE folder_configs ADD COLUMN uploadHiddenFiles INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN uploadSpeedBps INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE upload_jobs ADD COLUMN uploadNote TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_folder_configs_remoteFolderName " +
                    "ON folder_configs(remoteFolderName)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS wa_file_cache (" +
                    "relativePath TEXT NOT NULL PRIMARY KEY, " +
                    "lastModified INTEGER NOT NULL, " +
                    "fileSize INTEGER NOT NULL, " +
                    "hash TEXT NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "simplesynccompanion.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter fun fromJobStatus(v: JobStatus): String = v.name
    @TypeConverter fun toJobStatus(v: String): JobStatus = JobStatus.valueOf(v)
}
