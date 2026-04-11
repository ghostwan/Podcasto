package com.ghostwan.podcasto.data.backup

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.ghostwan.podcasto.data.repository.PodcastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GoogleDriveBackup"
private const val BACKUP_FILE_NAME = "podcasto_backup.json"
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * Represents a YouTube channel subscription from the user's Google account.
 */
data class YouTubeSubscription(
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
)

@Singleton
class GoogleDriveBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
) {
    private val _signedInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val signedInAccount: StateFlow<GoogleSignInAccount?> = _signedInAccount.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _lastBackupTime = MutableStateFlow<Long?>(null)
    val lastBackupTime: StateFlow<Long?> = _lastBackupTime.asStateFlow()

    private val driveScope = Scope(DriveScopes.DRIVE_APPDATA)
    private val youtubeScope = Scope(YouTubeScopes.YOUTUBE_READONLY)

    init {
        // Check if already signed in
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && GoogleSignIn.hasPermissions(account, driveScope)) {
            _signedInAccount.value = account
            // Load last backup time from prefs
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            _lastBackupTime.value = prefs.getLong("last_drive_backup", 0).takeIf { it > 0 }
        }
    }

    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope, youtubeScope)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** Check whether the signed-in account has the YouTube readonly scope. */
    fun hasYouTubeScope(): Boolean {
        val account = _signedInAccount.value ?: return false
        return GoogleSignIn.hasPermissions(account, youtubeScope)
    }

    fun onSignInResult(account: GoogleSignInAccount?) {
        _signedInAccount.value = account
        if (account != null) {
            Log.d(TAG, "Signed in as ${account.email}")
        }
    }

    fun signOut() {
        getSignInClient().signOut()
        _signedInAccount.value = null
        // Clear auto-backup pref
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().remove("auto_backup_drive").remove("last_drive_backup").apply()
        _lastBackupTime.value = null
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Podcasto")
            .build()
    }

    private fun getYouTubeService(account: GoogleSignInAccount): YouTube {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(YouTubeScopes.YOUTUBE_READONLY)
        )
        credential.selectedAccount = account.account
        return YouTube.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Podcasto")
            .build()
    }

    /**
     * Fetch all YouTube channel subscriptions from the signed-in Google account.
     * Returns a list of YouTubeSubscription (channelId, title, description, thumbnail).
     * Handles pagination to return all subscriptions.
     */
    suspend fun fetchYouTubeSubscriptions(): Result<List<YouTubeSubscription>> = withContext(Dispatchers.IO) {
        val account = _signedInAccount.value
            ?: return@withContext Result.failure(Exception("Not signed in"))

        try {
            val youtubeService = getYouTubeService(account)
            val subscriptions = mutableListOf<YouTubeSubscription>()
            var nextPageToken: String? = null

            do {
                val request = youtubeService.subscriptions().list(listOf("snippet"))
                    .setMine(true)
                    .setMaxResults(50)
                if (nextPageToken != null) {
                    request.pageToken = nextPageToken
                }
                val response = request.execute()

                response.items?.forEach { sub ->
                    val snippet = sub.snippet
                    val channelId = snippet?.resourceId?.channelId ?: return@forEach
                    subscriptions.add(
                        YouTubeSubscription(
                            channelId = channelId,
                            title = snippet.title ?: "",
                            description = snippet.description ?: "",
                            thumbnailUrl = snippet.thumbnails?.medium?.url
                                ?: snippet.thumbnails?.default?.url ?: "",
                        )
                    )
                }
                nextPageToken = response.nextPageToken
            } while (nextPageToken != null)

            Log.d(TAG, "Fetched ${subscriptions.size} YouTube subscriptions")
            Result.success(subscriptions.sortedBy { it.title.lowercase() })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch YouTube subscriptions", e)
            Result.failure(e)
        }
    }

    suspend fun backupToDrive(): Result<Unit> = withContext(Dispatchers.IO) {
        val account = _signedInAccount.value
            ?: return@withContext Result.failure(Exception("Not signed in"))

        _isBackingUp.value = true
        try {
            val driveService = getDriveService(account)
            val jsonData = repository.exportBackup()

            // Check if backup file already exists
            val existingFileId = findBackupFile(driveService)

            if (existingFileId != null) {
                // Update existing file
                val content = ByteArrayContent.fromString(BACKUP_MIME_TYPE, jsonData)
                driveService.files().update(existingFileId, null, content).execute()
                Log.d(TAG, "Updated existing backup")
            } else {
                // Create new file in appDataFolder
                val fileMetadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                val content = ByteArrayContent.fromString(BACKUP_MIME_TYPE, jsonData)
                driveService.files().create(fileMetadata, content)
                    .setFields("id")
                    .execute()
                Log.d(TAG, "Created new backup")
            }

            // Save last backup time
            val now = System.currentTimeMillis()
            _lastBackupTime.value = now
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_drive_backup", now).apply()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure(e)
        } finally {
            _isBackingUp.value = false
        }
    }

    suspend fun restoreFromDrive(): Result<Unit> = withContext(Dispatchers.IO) {
        val account = _signedInAccount.value
            ?: return@withContext Result.failure(Exception("Not signed in"))

        _isRestoring.value = true
        try {
            val driveService = getDriveService(account)
            val fileId = findBackupFile(driveService)
                ?: return@withContext Result.failure(Exception("No backup found on Google Drive"))

            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            val jsonData = outputStream.toString("UTF-8")

            repository.importBackup(jsonData)
            Log.d(TAG, "Restore completed")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            Result.failure(e)
        } finally {
            _isRestoring.value = false
        }
    }

    private fun findBackupFile(driveService: Drive): String? {
        val result = driveService.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id, name, modifiedTime)")
            .setPageSize(1)
            .execute()
        return result.files?.firstOrNull()?.id
    }

    fun isAutoBackupEnabled(): Boolean {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_backup_drive", false)
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_backup_drive", enabled).apply()
    }
}
