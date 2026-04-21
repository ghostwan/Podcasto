package com.ghostwan.podcasto.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghostwan.podcasto.BuildConfig
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.backup.GoogleDriveBackupManager
import com.ghostwan.podcasto.data.backup.YouTubeSubscription
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    repository: PodcastRepository,
    driveBackupManager: GoogleDriveBackupManager,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var geminiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var keyVisible by remember { mutableStateOf(false) }
    var webPassword by remember { mutableStateOf(prefs.getString("web_server_password", "") ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    val playerPrefs = remember { context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    var autoSelectOriginalLanguage by remember { mutableStateOf(playerPrefs.getBoolean("auto_select_original_language", true)) }
    var autoRefillPlaylist by remember { mutableStateOf(playerPrefs.getBoolean("auto_refill_playlist", false)) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    // YouTube subscriptions dialog state
    var showYouTubeSubsDialog by remember { mutableStateOf(false) }
    var youTubeSubsList by remember { mutableStateOf<List<YouTubeSubscription>>(emptyList()) }
    var isFetchingYouTubeSubs by remember { mutableStateOf(false) }
    var subscribedFeedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Google Drive state
    val signedInAccount by driveBackupManager.signedInAccount.collectAsState()
    val isBackingUp by driveBackupManager.isBackingUp.collectAsState()
    val isRestoring by driveBackupManager.isRestoring.collectAsState()
    val lastBackupTime by driveBackupManager.lastBackupTime.collectAsState()
    var autoBackupEnabled by remember { mutableStateOf(driveBackupManager.isAutoBackupEnabled()) }

    // Strings
    val keySavedMsg = stringResource(R.string.gemini_api_key_saved)
    val keyClearedMsg = stringResource(R.string.gemini_api_key_cleared)
    val backupSuccessMsg = stringResource(R.string.backup_export_success)
    val backupErrorMsg = stringResource(R.string.backup_export_error)
    val restoreSuccessMsg = stringResource(R.string.backup_import_success)
    val restoreErrorMsg = stringResource(R.string.backup_import_error)
    val driveBackupSuccessMsg = stringResource(R.string.drive_backup_success)
    val driveBackupErrorMsg = stringResource(R.string.drive_backup_error)
    val driveRestoreSuccessMsg = stringResource(R.string.drive_restore_success)
    val driveRestoreErrorMsg = stringResource(R.string.drive_restore_error)
    val driveSignInErrorMsg = stringResource(R.string.drive_sign_in_error)
    val passwordSavedMsg = stringResource(R.string.web_password_saved)
    val passwordClearedMsg = stringResource(R.string.web_password_cleared)
    val ytImportSuccessMsg = stringResource(R.string.youtube_import_success)
    val ytImportErrorMsg = stringResource(R.string.youtube_import_error)

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                driveBackupManager.onSignInResult(account)
            } catch (e: ApiException) {
                scope.launch {
                    snackbarHostState.showSnackbar("$driveSignInErrorMsg: ${e.statusCode}")
                }
            }
        }
    }

    // File picker for local import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                try {
                    val jsonString = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("Cannot read file")
                    repository.importBackup(jsonString)
                    geminiKey = prefs.getString("gemini_api_key", "") ?: ""
                    snackbarHostState.showSnackbar(restoreSuccessMsg)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("$restoreErrorMsg: ${e.message}")
                } finally {
                    isImporting = false
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ==========================================
            // Google Drive Backup section
            // ==========================================
            Text(
                text = stringResource(R.string.drive_backup_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.drive_backup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (signedInAccount == null) {
                // Sign-in button
                Button(
                    onClick = {
                        val signInIntent = driveBackupManager.getSignInClient().signInIntent
                        signInLauncher.launch(signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.drive_sign_in))
                }
            } else {
                // Signed in — show account info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.drive_signed_in_as, signedInAccount?.email ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (lastBackupTime != null && lastBackupTime!! > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val dateStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(lastBackupTime!!))
                            Text(
                                text = stringResource(R.string.drive_last_backup, dateStr),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Backup to Drive
                Button(
                    onClick = {
                        scope.launch {
                            val result = driveBackupManager.backupToDrive()
                            if (result.isSuccess) {
                                snackbarHostState.showSnackbar(driveBackupSuccessMsg)
                            } else {
                                snackbarHostState.showSnackbar(
                                    "$driveBackupErrorMsg: ${result.exceptionOrNull()?.message}"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBackingUp && !isRestoring,
                ) {
                    if (isBackingUp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.drive_backup_now))
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Restore from Drive
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = driveBackupManager.restoreFromDrive()
                            if (result.isSuccess) {
                                geminiKey = prefs.getString("gemini_api_key", "") ?: ""
                                snackbarHostState.showSnackbar(driveRestoreSuccessMsg)
                            } else {
                                snackbarHostState.showSnackbar(
                                    "$driveRestoreErrorMsg: ${result.exceptionOrNull()?.message}"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBackingUp && !isRestoring,
                ) {
                    if (isRestoring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.drive_restore))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Auto-backup toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.drive_auto_backup),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.drive_auto_backup_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = { enabled ->
                            autoBackupEnabled = enabled
                            driveBackupManager.setAutoBackupEnabled(enabled)
                            if (enabled) {
                                scheduleAutoBackup(context)
                            } else {
                                cancelAutoBackup(context)
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sign-out button
                TextButton(
                    onClick = {
                        driveBackupManager.signOut()
                        autoBackupEnabled = false
                        cancelAutoBackup(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.drive_sign_out))
                }

                // YouTube Subscriptions import (only when signed in + YouTube enabled)
                if (BuildConfig.YOUTUBE_ENABLED) {
                    Spacer(modifier = Modifier.height(16.dp))

                    HorizontalDivider()

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.youtube_subscriptions_title),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.youtube_subscriptions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isFetchingYouTubeSubs = true
                                // Load already subscribed feed URLs to mark them
                                val subscribed = repository.getAllSubscribedPodcasts()
                                subscribedFeedUrls = subscribed
                                    .filter { it.sourceType == "youtube" }
                                    .map { it.feedUrl }
                                    .toSet()
                                val result = driveBackupManager.fetchYouTubeSubscriptions()
                                isFetchingYouTubeSubs = false
                                if (result.isSuccess) {
                                    val subs = result.getOrDefault(emptyList())
                                    if (subs.isEmpty()) {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.youtube_no_subscriptions)
                                        )
                                    } else {
                                        youTubeSubsList = subs
                                        showYouTubeSubsDialog = true
                                    }
                                } else {
                                    snackbarHostState.showSnackbar(
                                        ytImportErrorMsg.format(result.exceptionOrNull()?.message ?: "")
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isFetchingYouTubeSubs,
                    ) {
                        if (isFetchingYouTubeSubs) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.youtube_import_subscriptions))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // Local Backup / Restore section
            // ==========================================
            Text(
                text = stringResource(R.string.backup_restore_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.backup_restore_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Export button
            Button(
                onClick = {
                    scope.launch {
                        isExporting = true
                        try {
                            val json = repository.exportBackup()
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val fileName = "podcasto_backup_$timestamp.json"
                            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            val file = File(downloadsDir, fileName)
                            file.writeText(json)
                            snackbarHostState.showSnackbar("$backupSuccessMsg: $fileName")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("$backupErrorMsg: ${e.message}")
                        } finally {
                            isExporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting && !isImporting,
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.backup_export))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Import button
            OutlinedButton(
                onClick = { importLauncher.launch("application/json") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isExporting && !isImporting,
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.backup_import))
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // ==========================================
            // Web Server Password section
            // ==========================================
            Text(
                text = stringResource(R.string.web_password_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.web_password_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = webPassword,
                onValueChange = { webPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.web_password_hint)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                        if (webPassword.isNotEmpty()) {
                            IconButton(onClick = {
                                webPassword = ""
                                prefs.edit().remove("web_server_password").apply()
                                scope.launch {
                                    snackbarHostState.showSnackbar(passwordClearedMsg)
                                }
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    prefs.edit().putString("web_server_password", webPassword.trim()).apply()
                    scope.launch {
                        snackbarHostState.showSnackbar(passwordSavedMsg)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = webPassword.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }

            if (webPassword.isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.web_password_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                val savedPwd = prefs.getString("web_server_password", "") ?: ""
                if (savedPwd.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.web_password_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // Gemini API Key section
            // ==========================================
            Text(
                text = stringResource(R.string.gemini_api_key),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.gemini_api_key_hint)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                        if (geminiKey.isNotEmpty()) {
                            IconButton(onClick = {
                                geminiKey = ""
                                prefs.edit().remove("gemini_api_key").apply()
                                scope.launch {
                                    snackbarHostState.showSnackbar(keyClearedMsg)
                                }
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    prefs.edit().putString("gemini_api_key", geminiKey.trim()).apply()
                    scope.launch {
                        snackbarHostState.showSnackbar(keySavedMsg)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = geminiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // Playback section
            // ==========================================
            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.playback_settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Auto-refill playlist toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auto_refill_playlist),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.auto_refill_playlist_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoRefillPlaylist,
                    onCheckedChange = { enabled ->
                        autoRefillPlaylist = enabled
                        playerPrefs.edit().putBoolean("auto_refill_playlist", enabled).apply()
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // YouTube Playback section
            // ==========================================
            if (BuildConfig.YOUTUBE_ENABLED) {
                HorizontalDivider()

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.youtube_playback_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Auto-select original language toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.auto_select_original_language),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.auto_select_original_language_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoSelectOriginalLanguage,
                        onCheckedChange = { enabled ->
                            autoSelectOriginalLanguage = enabled
                            playerPrefs.edit().putBoolean("auto_select_original_language", enabled).apply()
                        },
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Guide section
            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.gemini_guide_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.gemini_guide_step1),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_guide_step2),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_guide_step3),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gemini_guide_step4),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.gemini_guide_url)))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.gemini_guide_link))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ==========================================
            // Version
            // ==========================================
            HorizontalDivider()

            Spacer(modifier = Modifier.height(16.dp))

            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/ghostwan/Podcasto"),
                            )
                            context.startActivity(intent)
                        },
                    )
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // YouTube Subscriptions selection dialog
    if (showYouTubeSubsDialog) {
        YouTubeSubscriptionsDialog(
            subscriptions = youTubeSubsList,
            subscribedFeedUrls = subscribedFeedUrls,
            onDismiss = { showYouTubeSubsDialog = false },
            onImport = { selectedChannelIds ->
                showYouTubeSubsDialog = false
                scope.launch {
                    var count = 0
                    for (channelId in selectedChannelIds) {
                        try {
                            val channelUrl = "https://www.youtube.com/channel/$channelId"
                            repository.subscribeToYouTubeChannel(channelUrl)
                            count++
                        } catch (e: Exception) {
                            android.util.Log.e("Settings", "Failed to subscribe to $channelId", e)
                        }
                    }
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.youtube_import_success, count)
                    )
                }
            },
        )
    }
}

private fun scheduleAutoBackup(context: Context) {
    val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.ghostwan.podcasto.data.backup.AutoBackupWorker>(
        24, java.util.concurrent.TimeUnit.HOURS,
    )
        .setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        )
        .build()

    androidx.work.WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
            com.ghostwan.podcasto.data.backup.AutoBackupWorker.WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
}

private fun cancelAutoBackup(context: Context) {
    androidx.work.WorkManager.getInstance(context)
        .cancelUniqueWork(com.ghostwan.podcasto.data.backup.AutoBackupWorker.WORK_NAME)
}

/**
 * Full-screen dialog showing YouTube subscriptions from the user's Google account.
 * Allows selecting channels to subscribe to in Podcasto.
 * Already-subscribed channels are marked and excluded from selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeSubscriptionsDialog(
    subscriptions: List<YouTubeSubscription>,
    subscribedFeedUrls: Set<String>,
    onDismiss: () -> Unit,
    onImport: (List<String>) -> Unit,
) {
    // Determine which channels are already subscribed
    val alreadySubscribedIds = remember(subscribedFeedUrls) {
        subscribedFeedUrls.mapNotNull { url ->
            url.substringAfter("channel_id=", "").takeIf { it.isNotEmpty() }
        }.toSet()
    }

    // Track selected channels (default: none; already subscribed are excluded)
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
    val selectableCount = subscriptions.count { it.channelId !in alreadySubscribedIds }
    val selectedCount = selectedIds.count { it.value && it.key !in alreadySubscribedIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.youtube_select_subscriptions, selectableCount))
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
            ) {
                items(subscriptions, key = { it.channelId }) { sub ->
                    val isAlreadySubscribed = sub.channelId in alreadySubscribedIds
                    val isSelected = selectedIds[sub.channelId] == true

                    ListItem(
                        modifier = Modifier.fillMaxWidth(),
                        headlineContent = {
                            Text(
                                text = sub.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isAlreadySubscribed)
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = if (isAlreadySubscribed) {
                            {
                                Text(
                                    text = stringResource(R.string.youtube_already_subscribed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else null,
                        leadingContent = {
                            AsyncImage(
                                model = sub.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        },
                        trailingContent = {
                            if (isAlreadySubscribed) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds[sub.channelId] = checked
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ids = selectedIds.filter { it.value && it.key !in alreadySubscribedIds }.keys.toList()
                    onImport(ids)
                },
                enabled = selectedCount > 0,
            ) {
                Text(stringResource(R.string.youtube_import_selected, selectedCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
