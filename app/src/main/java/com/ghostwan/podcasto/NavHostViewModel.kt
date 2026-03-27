package com.ghostwan.podcasto

import androidx.lifecycle.ViewModel
import com.ghostwan.podcasto.data.backup.GoogleDriveBackupManager
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class NavHostViewModel @Inject constructor(
    val playerManager: PlayerManager,
    val repository: PodcastRepository,
    val driveBackupManager: GoogleDriveBackupManager,
) : ViewModel() {

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    fun toggleShowHidden() {
        _showHidden.value = !_showHidden.value
    }
}
