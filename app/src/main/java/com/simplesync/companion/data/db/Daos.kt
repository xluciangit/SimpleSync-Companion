package com.simplesync.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderConfigDao {
    @Query("SELECT * FROM folder_configs ORDER BY id ASC")
    fun getAllFlow(): Flow<List<FolderConfig>>

    @Query("SELECT * FROM folder_configs WHERE remoteFolderName = :name LIMIT 1")
    suspend fun getByRemoteName(name: String): FolderConfig?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(config: FolderConfig): Long

    @Update
    suspend fun update(config: FolderConfig)

    @Delete
    suspend fun delete(config: FolderConfig)

    @Query("DELETE FROM folder_configs")
    suspend fun deleteAll()

    @Query("UPDATE folder_configs SET lastScanAt = :ts WHERE id = :id")
    suspend fun updateLastScan(id: Long, ts: Long)
}

@Dao
interface TrackedFileDao {
    @Query("SELECT * FROM tracked_files WHERE folderConfigId = :cfgId AND relativePath = :path LIMIT 1")
    suspend fun find(cfgId: Long, path: String): TrackedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: TrackedFile)

    @Query("DELETE FROM tracked_files WHERE folderConfigId = :cfgId")
    suspend fun deleteForConfig(cfgId: Long)

    @Query("DELETE FROM tracked_files")
    suspend fun deleteAll()
}

@Dao
interface UploadJobDao {
    @Query("SELECT * FROM upload_jobs ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<UploadJob>>

    @Query("SELECT * FROM upload_jobs WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<UploadJob>

    @Query("SELECT COUNT(*) FROM upload_jobs WHERE status = 'PENDING' OR status = 'UPLOADING'")
    fun pendingCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: UploadJob): Long

    @Query("SELECT * FROM upload_jobs WHERE folderConfigId = :cfgId AND relativePath = :path AND status NOT IN ('COMPLETED','SKIPPED','CANCELLED') LIMIT 1")
    suspend fun findActive(cfgId: Long, path: String): UploadJob?

    @Query("UPDATE upload_jobs SET status = :status, errorMessage = :err, completedAt = :ts, progressBytes = :prog, uploadSpeedBps = 0 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: JobStatus, err: String? = null, ts: Long? = null, prog: Long = 0)

    @Query("UPDATE upload_jobs SET progressBytes = :bytes, uploadSpeedBps = :speedBps WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, speedBps: Long = 0)

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE status = 'FAILED'")
    suspend fun retryAllFailed()

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE id = :id")
    suspend fun retryJob(id: Long)

    @Query("UPDATE upload_jobs SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'")
    suspend fun cancelJob(id: Long)

    @Query("DELETE FROM upload_jobs WHERE status = 'COMPLETED' OR status = 'SKIPPED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM upload_jobs WHERE id = :id AND (status = 'COMPLETED' OR status = 'SKIPPED')")
    suspend fun clearSingleCompleted(id: Long)

    @Query("DELETE FROM upload_jobs WHERE status = 'CANCELLED'")
    suspend fun clearCancelled()

    @Query("DELETE FROM upload_jobs")
    suspend fun deleteAllJobs()

    @Query("DELETE FROM upload_jobs WHERE folderConfigId = :cfgId")
    suspend fun deleteForConfig(cfgId: Long)

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE status = 'UPLOADING' OR status = 'HASHING'")
    suspend fun resetStuckUploading()

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE folderConfigId = :cfgId AND status = 'FAILED' AND errorMessage LIKE '%File too big%'")
    suspend fun retryTooBigJobs(cfgId: Long)

    @Query("UPDATE upload_jobs SET uploadNote = :note WHERE id = :id")
    suspend fun updateUploadNote(id: Long, note: String?)

    @Query("DELETE FROM upload_jobs WHERE folderConfigId = -1 AND status NOT IN ('UPLOADING','HASHING')")
    suspend fun clearPreviousWaJobs()

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE folderConfigId = -1 AND (status = 'UPLOADING' OR status = 'HASHING')")
    suspend fun resetStuckWaJobs()

    @Query("SELECT * FROM upload_jobs WHERE folderConfigId = -1 AND relativePath = :path LIMIT 1")
    suspend fun findWaJobByPath(path: String): UploadJob?

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL, progressBytes = 0, uploadSpeedBps = 0, fileUriString = :uri WHERE id = :id")
    suspend fun resetWaJobForRetry(id: Long, uri: String)

}

@Dao
interface WaFileCacheDao {
    @Query("SELECT * FROM wa_file_cache WHERE relativePath = :path LIMIT 1")
    suspend fun find(path: String): WaFileCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WaFileCache)

    @Query("DELETE FROM wa_file_cache")
    suspend fun deleteAll()
}
