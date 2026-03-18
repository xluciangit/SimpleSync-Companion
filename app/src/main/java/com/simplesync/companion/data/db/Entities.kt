package com.simplesync.companion.data.db

import androidx.room.*

@Entity(
    tableName = "folder_configs",
    indices = [Index(value = ["remoteFolderName"], unique = true)]
)
data class FolderConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val localUri: String,
    val remoteFolderName: String,
    val scanIntervalMinutes: Int = 60,
    val lastScanAt: Long = 0,
    val isActive: Boolean = true,
    val uploadHiddenFiles: Boolean = false
)

@Entity(
    tableName = "tracked_files",
    indices = [Index(value = ["folderConfigId", "relativePath"], unique = true)]
)
data class TrackedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderConfigId: Long,
    val relativePath: String,
    val lastModified: Long,
    val fileSize: Long,
    val sha256Hash: String? = null,
    val uploadedAt: Long = System.currentTimeMillis()
)

enum class JobStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    
    SKIPPED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "upload_jobs")
data class UploadJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderConfigId: Long,
    val remoteFolderName: String,
    val relativePath: String,
    val fileUriString: String,
    val fileName: String,
    val fileSize: Long,
    @ColumnInfo(name = "status") val status: JobStatus = JobStatus.PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val progressBytes: Long = 0,
    val uploadSpeedBps: Long = 0,
    
    val uploadNote: String? = null
)
