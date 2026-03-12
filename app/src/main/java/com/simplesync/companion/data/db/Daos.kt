package com.simplesync.companion.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── FolderConfigDao ───────────────────────────────────────────────────────────

@Dao
interface FolderConfigDao {
    @Query("SELECT * FROM folder_configs ORDER BY id ASC")
    fun getAllFlow(): Flow<List<FolderConfig>>

    @Query("SELECT * FROM folder_configs ORDER BY id ASC")
    suspend fun getAll(): List<FolderConfig>

    @Query("SELECT * FROM folder_configs WHERE isActive = 1 ORDER BY id ASC")
    suspend fun getActive(): List<FolderConfig>

    @Query("SELECT * FROM folder_configs WHERE id = :id")
    suspend fun getById(id: Long): FolderConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: FolderConfig): Long

    @Update
    suspend fun update(config: FolderConfig)

    @Delete
    suspend fun delete(config: FolderConfig)

    @Query("UPDATE folder_configs SET lastScanAt = :ts WHERE id = :id")
    suspend fun updateLastScan(id: Long, ts: Long)
}

// ── TrackedFileDao ────────────────────────────────────────────────────────────

@Dao
interface TrackedFileDao {
    @Query("SELECT * FROM tracked_files WHERE folderConfigId = :cfgId AND relativePath = :path LIMIT 1")
    suspend fun find(cfgId: Long, path: String): TrackedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: TrackedFile)

    @Query("DELETE FROM tracked_files WHERE folderConfigId = :cfgId")
    suspend fun deleteForConfig(cfgId: Long)
}

// ── UploadJobDao ──────────────────────────────────────────────────────────────

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

    @Query("SELECT * FROM upload_jobs WHERE folderConfigId = :cfgId AND relativePath = :path AND status NOT IN ('COMPLETED','CANCELLED') LIMIT 1")
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

    @Query("DELETE FROM upload_jobs WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM upload_jobs WHERE id = :id AND status = 'COMPLETED'")
    suspend fun clearSingleCompleted(id: Long)

    @Query("DELETE FROM upload_jobs WHERE status = 'CANCELLED'")
    suspend fun clearCancelled()

    @Query("UPDATE upload_jobs SET status = 'CANCELLED' WHERE status = 'PENDING'")
    suspend fun cancelAllPending()

    @Query("DELETE FROM upload_jobs WHERE folderConfigId = :cfgId")
    suspend fun deleteForConfig(cfgId: Long)

    @Query("UPDATE upload_jobs SET status = 'PENDING', errorMessage = NULL WHERE status = 'UPLOADING'")
    suspend fun resetStuckUploading()
}
