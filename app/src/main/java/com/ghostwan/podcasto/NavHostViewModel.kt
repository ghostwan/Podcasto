package com.ghostwan.podcasto

import android.content.Context
import androidx.lifecycle.ViewModel
import com.ghostwan.podcasto.data.backup.GoogleDriveBackupManager
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class NavHostViewModel @Inject constructor(
    @ApplicationContext context: Context,
    val playerManager: PlayerManager,
    val repository: PodcastRepository,
    val driveBackupManager: GoogleDriveBackupManager,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    private val _hideYoutube = MutableStateFlow(prefs.getBoolean("hide_youtube", false))
    val hideYoutube: StateFlow<Boolean> = _hideYoutube.asStateFlow()

    fun toggleShowHidden() {
        _showHidden.value = !_showHidden.value
    }

    fun toggleHideYoutube() {
        val newValue = !_hideYoutube.value
        _hideYoutube.value = newValue
        prefs.edit().putBoolean("hide_youtube", newValue).apply()
    }
}
