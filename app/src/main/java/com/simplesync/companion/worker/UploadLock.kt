package com.simplesync.companion.worker

import kotlinx.coroutines.sync.Mutex

// prevents UploadWorker and WhatsAppBackupWorker from running concurrently
object UploadLock {
    val mutex = Mutex()
}
