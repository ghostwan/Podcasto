package com.music.podcasto

import androidx.lifecycle.ViewModel
import com.music.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavHostViewModel @Inject constructor(
    val playerManager: PlayerManager,
) : ViewModel()
