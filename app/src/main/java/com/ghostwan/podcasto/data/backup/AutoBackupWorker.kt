package com.ghostwan.podcasto.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "AutoBackupWorker"

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupManager: GoogleDriveBackupManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (backupManager.signedInAccount.value == null) {
            Log.d(TAG, "Not signed in, skipping auto-backup")
            return Result.success()
        }

        if (!backupManager.isAutoBackupEnabled()) {
            Log.d(TAG, "Auto-backup disabled, skipping")
            return Result.success()
        }

        Log.d(TAG, "Starting auto-backup to Google Drive")
        val result = backupManager.backupToDrive()
        return if (result.isSuccess) {
            Log.d(TAG, "Auto-backup completed successfully")
            Result.success()
        } else {
            Log.e(TAG, "Auto-backup failed: ${result.exceptionOrNull()?.message}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "podcasto_auto_backup"
    }
}
