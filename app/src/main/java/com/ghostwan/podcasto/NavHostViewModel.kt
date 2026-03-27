package com.ghostwan.podcasto

import androidx.lifecycle.ViewModel
import com.ghostwan.podcasto.data.backup.GoogleDriveBackupManager
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavHostViewModel @Inject constructor(
    val playerManager: PlayerManager,
    val repository: PodcastRepository,
    val driveBackupManager: GoogleDriveBackupManager,
) : ViewModel()
