package com.ghostwan.podcasto.player

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ghostwan.podcasto.BuildConfig
import com.ghostwan.podcasto.data.local.EpisodeEntity
import com.ghostwan.podcasto.data.remote.YouTubeExtractor
import com.ghostwan.podcasto.data.repository.PodcastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerState(
    val currentEpisode: EpisodeEntity? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val podcastArtworkUrl: String = "",
    val podcastSourceType: String = "rss",
    val volumeNormEnabled: Boolean = false,
    val isVideoMode: Boolean = false,
)

/**
 * Emitted when a YouTube episode has multiple audio languages available.
 * The UI should show a picker and call PlayerManager.playWithLanguage().
 */
data class LanguageSelectionRequest(
    val episode: EpisodeEntity,
    val artworkUrl: String,
    /** language code -> display name, e.g. "fr" -> "Français" */
    val availableLanguages: Map<String, String>,
)

/**
 * Emitted when the user tries to play an episode that was already fully played.
 * The UI should show a dialog asking whether to restart from the beginning or resume.
 */
data class RestartRequest(
    val episode: EpisodeEntity,
    val artworkUrl: String,
)

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /** Emitted when a YouTube episode has multiple audio languages; UI should show a picker. */
    private val _languageSelectionRequest = MutableStateFlow<LanguageSelectionRequest?>(null)
    val languageSelectionRequest: StateFlow<LanguageSelectionRequest?> = _languageSelectionRequest.asStateFlow()

    /** Emitted when user plays an already-finished episode; UI should ask restart or resume. */
    private val _restartRequest = MutableStateFlow<RestartRequest?>(null)
    val restartRequest: StateFlow<RestartRequest?> = _restartRequest.asStateFlow()

    private var currentEpisode: EpisodeEntity? = null
    private var currentArtworkUrl: String = ""
    private var currentSourceType: String = "rss"
    private var positionPollingJob: Job? = null
    private var volumeNormEnabled: Boolean = false
    // Guard flag: true while switching media items, to ignore spurious STATE_ENDED
    // that Media3 fires when replacing the current media item.
    private var isChangingMedia: Boolean = false
    private var isVideoMode: Boolean = false
    /** Remember video mode preference so it persists across episode transitions. */
    var preferVideoMode: Boolean = false
        private set
    /** Remembers the language code selected by the user (for audio↔video switch preservation). */
    private var currentLanguageCode: String? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    private fun saveLastEpisode(episodeId: Long, artworkUrl: String, sourceType: String) {
        prefs.edit()
            .putLong("last_episode_id", episodeId)
            .putString("last_artwork_url", artworkUrl)
            .putString("last_source_type", sourceType)
            .apply()
    }

    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = scope.launch {
            while (true) {
                delay(500)
                if (controller?.isPlaying == true) {
                    updateState()
                }
            }
        }
    }

    fun initialize() {
        if (controller != null) return

        // Load volume normalization preference
        volumeNormEnabled = prefs.getBoolean("volume_normalization", false)

        // Restore last episode from SharedPreferences (show in mini-player, paused)
        val lastEpisodeId = prefs.getLong("last_episode_id", -1)
        if (lastEpisodeId != -1L && currentEpisode == null) {
            val lastArtwork = prefs.getString("last_artwork_url", "") ?: ""
            val lastSourceType = prefs.getString("last_source_type", "rss") ?: "rss"
            scope.launch {
                val episode = repository.getEpisodeById(lastEpisodeId)
                if (episode != null) {
                    currentEpisode = episode
                    currentArtworkUrl = lastArtwork
                    currentSourceType = lastSourceType
                    _playerState.value = PlayerState(
                        currentEpisode = episode,
                        isPlaying = false,
                        currentPosition = episode.playbackPosition,
                        duration = 0,
                        podcastArtworkUrl = lastArtwork,
                        podcastSourceType = lastSourceType,
                        volumeNormEnabled = volumeNormEnabled,
                        isVideoMode = false,
                    )
                }
            }
        }

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.let {
                try {
                    it.get()
                } catch (e: Exception) {
                    null
                }
            }
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                    // Save position when pausing
                    if (!isPlaying) {
                        saveCurrentPosition()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateState()
                    // Only mark as played when episode genuinely ended — ignore spurious
                    // STATE_ENDED events fired by Media3 when replacing the media item.
                    if (playbackState == Player.STATE_ENDED
                        && !isChangingMedia
                        && (controller?.duration ?: 0) > 0
                    ) {
                        Log.d("PlayerManager", "Episode ended: ${currentEpisode?.title}")
                        markCurrentAsPlayed()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PlayerManager", "Player error: ${error.errorCodeName} - ${error.message}", error)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateState()
                }
            })
            startPositionPolling()
        }, MoreExecutors.directExecutor())
    }

    fun play(episode: EpisodeEntity, artworkUrl: String = "", forceRestart: Boolean = false) {
        // If episode was already played, ask user whether to restart from beginning
        if (episode.played && !forceRestart && episode.id != currentEpisode?.id) {
            _restartRequest.value = RestartRequest(episode, artworkUrl)
            return
        }

        // Save position of currently playing episode before switching
        saveCurrentPosition()

        // Prevent spurious STATE_ENDED from marking the outgoing episode as played
        isChangingMedia = true

        currentArtworkUrl = artworkUrl

        // Reload episode from DB to get the latest playback position
        scope.launch {
            val freshEpisode = repository.getEpisodeById(episode.id) ?: episode
            currentEpisode = freshEpisode

            // Look up podcast sourceType for YouTube badge
            val podcast = repository.getPodcastById(freshEpisode.podcastId)
            currentSourceType = podcast?.sourceType ?: "rss"
            saveLastEpisode(freshEpisode.id, artworkUrl, currentSourceType)

            // For YouTube episodes without a local download, check for multiple languages
            if (BuildConfig.YOUTUBE_ENABLED && freshEpisode.downloadPath == null && (YouTubeExtractor.isYouTubeVideoUrl(freshEpisode.audioUrl) || currentSourceType == "youtube")) {
                try {
                    val langOptions = repository.getAvailableLanguages(freshEpisode)
                    if (langOptions != null && langOptions.availableLanguages.size > 1) {
                        // Check if auto-select original language is enabled
                        val autoSelectOriginal = prefs.getBoolean("auto_select_original_language", true)
                        val originalCode = langOptions.originalLanguageCode
                        if (autoSelectOriginal && originalCode != null) {
                            // Auto-select the original language
                            android.util.Log.d("PlayerManager", "Auto-selecting original language: $originalCode")
                            currentLanguageCode = originalCode
                            val resolved = repository.resolveAudioUrlForLanguage(freshEpisode, originalCode)
                            if (resolved.durationSeconds > 0 && freshEpisode.duration == 0L) {
                                repository.updateEpisodeDuration(freshEpisode.id, resolved.durationSeconds)
                            }
                            repository.addHistoryEntry(freshEpisode.id, freshEpisode.podcastId)
                            repository.addToPlaylistTop(freshEpisode.id)
                            startMediaPlayback(freshEpisode, artworkUrl, Uri.parse(resolved.url))
                            return@launch
                        }
                        // Multiple languages available and no auto-select — ask user to choose
                        isChangingMedia = false
                        _languageSelectionRequest.value = LanguageSelectionRequest(
                            episode = freshEpisode,
                            artworkUrl = artworkUrl,
                            availableLanguages = langOptions.availableLanguages,
                        )
                        return@launch
                    }
                    // Single or no language — use the default audio URL from the same call
                    // (avoids a second StreamInfo.getInfo() network call)
                    if (langOptions != null && langOptions.defaultAudioUrl.isNotEmpty()) {
                        if (langOptions.durationSeconds > 0 && freshEpisode.duration == 0L) {
                            repository.updateEpisodeDuration(freshEpisode.id, langOptions.durationSeconds)
                        }
                        repository.addHistoryEntry(freshEpisode.id, freshEpisode.podcastId)
                        repository.addToPlaylistTop(freshEpisode.id)
                        startMediaPlayback(freshEpisode, artworkUrl, Uri.parse(langOptions.defaultAudioUrl))
                        return@launch
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PlayerManager", "Language check failed, proceeding with default: ${e.message}")
                }
            }

            // No language selection needed — play directly
            playInternal(freshEpisode, artworkUrl)
        }
    }

    /**
     * Called by the UI after the user picks a language from the language selection dialog.
     */
    fun playWithLanguage(episode: EpisodeEntity, artworkUrl: String, languageCode: String) {
        _languageSelectionRequest.value = null
        isChangingMedia = true
        currentLanguageCode = languageCode
        scope.launch {
            val freshEpisode = repository.getEpisodeById(episode.id) ?: episode
            currentEpisode = freshEpisode
            val podcast = repository.getPodcastById(freshEpisode.podcastId)
            currentSourceType = podcast?.sourceType ?: "rss"
            saveLastEpisode(freshEpisode.id, artworkUrl, currentSourceType)

            // Record in listening history
            repository.addHistoryEntry(freshEpisode.id, freshEpisode.podcastId)
            repository.addToPlaylistTop(freshEpisode.id)

            val audioUri = try {
                val resolved = repository.resolveAudioUrlForLanguage(freshEpisode, languageCode)
                if (resolved.durationSeconds > 0 && freshEpisode.duration == 0L) {
                    repository.updateEpisodeDuration(freshEpisode.id, resolved.durationSeconds)
                }
                Uri.parse(resolved.url)
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Failed to resolve audio URL for language $languageCode", e)
                isChangingMedia = false
                _playerState.value = _playerState.value.copy(
                    currentEpisode = freshEpisode,
                    isPlaying = false,
                )
                return@launch
            }

            startMediaPlayback(freshEpisode, artworkUrl, audioUri)
        }
    }

    /**
     * Called by the UI if the user dismisses the language selection dialog.
     */
    fun dismissLanguageSelection() {
        _languageSelectionRequest.value = null
        isChangingMedia = false
    }

    /**
     * Called by the UI when user chooses to restart a played episode from the beginning.
     */
    fun restartFromBeginning() {
        val request = _restartRequest.value ?: return
        _restartRequest.value = null
        scope.launch {
            repository.updatePlaybackPosition(request.episode.id, 0)
            repository.markAsUnplayed(request.episode.id)
            val freshEp = repository.getEpisodeById(request.episode.id) ?: request.episode
            play(freshEp.copy(playbackPosition = 0, played = false), request.artworkUrl, forceRestart = true)
        }
    }

    /**
     * Called by the UI when user chooses to skip a played episode (play next in playlist).
     */
    fun skipPlayed() {
        val request = _restartRequest.value ?: return
        _restartRequest.value = null
        scope.launch {
            // Remove the skipped episode from playlist
            repository.removeFromPlaylist(request.episode.id)
            // Play next episode in playlist if available
            val playlist = repository.getPlaylistEpisodesWithArtworkList()
            if (playlist.isNotEmpty()) {
                val next = playlist.first()
                play(next.episode, next.artworkUrl)
            }
        }
    }

    /**
     * Called by the UI if the user dismisses the restart dialog.
     */
    fun dismissRestartRequest() {
        _restartRequest.value = null
    }

    /**
     * Internal: play an episode without language check (already determined).
     */
    private suspend fun playInternal(freshEpisode: EpisodeEntity, artworkUrl: String) {
        // Reset language code when playing without explicit language selection
        currentLanguageCode = null

        // Record in listening history
        repository.addHistoryEntry(freshEpisode.id, freshEpisode.podcastId)

        // Auto-add to top of playlist if not already in it
        repository.addToPlaylistTop(freshEpisode.id)

        val audioUri = if (freshEpisode.downloadPath != null) {
            Uri.parse(freshEpisode.downloadPath)
        } else {
            // Resolve YouTube URLs at play time (they expire)
            try {
                val resolved = repository.resolveAudioUrl(freshEpisode)
                // If YouTube duration was resolved and episode had no duration, save it
                if (resolved.durationSeconds > 0 && freshEpisode.duration == 0L) {
                    repository.updateEpisodeDuration(freshEpisode.id, resolved.durationSeconds)
                }
                Uri.parse(resolved.url)
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "Failed to resolve audio URL for episode ${freshEpisode.id}", e)
                isChangingMedia = false
                _playerState.value = _playerState.value.copy(
                    currentEpisode = freshEpisode,
                    isPlaying = false,
                )
                return
            }
        }

        startMediaPlayback(freshEpisode, artworkUrl, audioUri)
    }

    /**
     * Internal: actually start playing a media item (shared by playInternal and playWithLanguage).
     */
    private fun startMediaPlayback(episode: EpisodeEntity, artworkUrl: String, audioUri: Uri) {
        // Reset video mode when starting a new episode
        isVideoMode = false
        val mediaItem = MediaItem.Builder()
            .setUri(audioUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setDescription(episode.description)
                    .setArtworkUri(if (artworkUrl.isNotEmpty()) Uri.parse(artworkUrl) else null)
                    .build()
            )
            .build()
        // Use setMediaItem with startPositionMs so the player seeks after prepare
        val startPos = if (episode.playbackPosition > 0) episode.playbackPosition else 0L
        // Emit clean state immediately to avoid stale position from previous episode
        _playerState.value = PlayerState(
            currentEpisode = episode,
            isPlaying = false,
            currentPosition = startPos,
            duration = 0,
            podcastArtworkUrl = artworkUrl,
            podcastSourceType = currentSourceType,
            volumeNormEnabled = volumeNormEnabled,
            isVideoMode = false,
        )
        controller?.setMediaItem(mediaItem, startPos)
        controller?.prepare()
        controller?.play()
        isChangingMedia = false
        updateState()

        // If the user was in video mode and the new episode is also YouTube,
        // automatically switch back to video mode after audio starts.
        if (preferVideoMode && currentSourceType == "youtube") {
            scope.launch {
                // Small delay to let audio playback initialize
                kotlinx.coroutines.delay(500)
                if (currentEpisode?.id == episode.id && !isVideoMode) {
                    toggleVideoMode()
                }
            }
        }
    }

    fun playMultiple(episodes: List<EpisodeEntity>, startIndex: Int = 0, artworkUrl: String = "") {
        if (episodes.isEmpty()) return
        // Play only the first episode; auto-advance (markCurrentAsPlayed) will
        // chain to the next playlist item. This ensures YouTube URLs are resolved
        // individually at play time instead of passing raw URLs to Media3's internal playlist.
        play(episodes[startIndex], artworkUrl)
    }

    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) {
                it.pause()
            } else if (it.mediaItemCount > 0) {
                // Controller has media loaded, just resume
                it.play()
            } else {
                // No media loaded (e.g. after app restart) — reload the restored episode
                val ep = currentEpisode ?: return@let
                play(ep, currentArtworkUrl)
                return
            }
        }
        updateState()
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
        updateState()
    }

    fun skipForward(ms: Long = 30_000) {
        controller?.let {
            it.seekTo(minOf(it.currentPosition + ms, it.duration))
        }
    }

    fun skipBackward(ms: Long = 10_000) {
        controller?.let {
            it.seekTo(maxOf(it.currentPosition - ms, 0))
        }
    }

    fun playNext() {
        val ep = currentEpisode ?: return
        scope.launch {
            val playlist = repository.getPlaylistEpisodesWithArtworkList()
            val currentIndex = playlist.indexOfFirst { it.episode.id == ep.id }
            if (currentIndex >= 0 && currentIndex < playlist.size - 1) {
                val next = playlist[currentIndex + 1]
                play(next.episode, next.artworkUrl)
            }
        }
    }

    fun playPrevious() {
        val ep = currentEpisode ?: return
        scope.launch {
            val playlist = repository.getPlaylistEpisodesWithArtworkList()
            val currentIndex = playlist.indexOfFirst { it.episode.id == ep.id }
            if (currentIndex > 0) {
                val prev = playlist[currentIndex - 1]
                play(prev.episode, prev.artworkUrl)
            }
        }
    }

    fun stop() {
        saveCurrentPosition()
        isChangingMedia = true
        isVideoMode = false
        preferVideoMode = false
        controller?.stop()
        isChangingMedia = false
        currentEpisode = null
        prefs.edit().remove("last_episode_id").remove("last_artwork_url").apply()
        updateState()
    }

    fun getCurrentPositionMs(): Long = controller?.currentPosition ?: 0

    fun toggleVolumeNormalization() {
        volumeNormEnabled = !volumeNormEnabled
        prefs.edit().putBoolean("volume_normalization", volumeNormEnabled).apply()
        // Send custom command to PlaybackService to toggle LoudnessEnhancer
        val args = Bundle().apply { putBoolean("enabled", volumeNormEnabled) }
        controller?.sendCustomCommand(
            SessionCommand("VOLUME_NORM_TOGGLE", Bundle.EMPTY),
            args,
        )
        updateState()
    }

    /**
     * Toggle between audio-only and video mode for the current YouTube episode.
     * Preserves the current playback position across the switch.
     * Uses local files when available (offline), otherwise resolves URLs from YouTube.
     */
    fun toggleVideoMode() {
        val ep = currentEpisode ?: return
        if (currentSourceType != "youtube") return

        val currentPos = controller?.currentPosition ?: 0L

        isChangingMedia = true

        if (isVideoMode) {
            // Switch back to audio-only mode
            isVideoMode = false
            preferVideoMode = false
            scope.launch {
                try {
                    // Reload from DB to get latest download paths
                    val freshEp = repository.getEpisodeById(ep.id) ?: ep
                    val audioUri = if (freshEp.downloadPath != null) {
                        Uri.parse(freshEp.downloadPath)
                    } else if (currentLanguageCode != null) {
                        // Preserve selected language when switching back to audio
                        val resolved = repository.resolveAudioUrlForLanguage(freshEp, currentLanguageCode!!)
                        Uri.parse(resolved.url)
                    } else {
                        val resolved = repository.resolveAudioUrl(freshEp)
                        Uri.parse(resolved.url)
                    }
                    val mediaItem = MediaItem.Builder()
                        .setUri(audioUri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(ep.title)
                                .setDescription(ep.description)
                                .setArtworkUri(if (currentArtworkUrl.isNotEmpty()) Uri.parse(currentArtworkUrl) else null)
                                .build()
                        )
                        .build()
                    controller?.setMediaItem(mediaItem, currentPos)
                    controller?.prepare()
                    controller?.play()
                } catch (e: Exception) {
                    android.util.Log.e("PlayerManager", "Failed to switch to audio mode", e)
                }
                isChangingMedia = false
                updateState()
            }
        } else {
            // Switch to video mode — need MergingMediaSource via PlaybackService custom command
            isVideoMode = true
            preferVideoMode = true
            scope.launch {
                try {
                    // Reload from DB to get latest download paths
                    val freshEp = repository.getEpisodeById(ep.id) ?: ep

                    // Check for local offline video + audio files first
                    if (freshEp.videoDownloadPath != null && freshEp.downloadPath != null) {
                        val args = Bundle().apply {
                            putString("video_url", freshEp.videoDownloadPath)
                            putString("audio_url", freshEp.downloadPath)
                            putLong("position_ms", currentPos)
                            putString("title", ep.title)
                            putString("artwork_url", currentArtworkUrl)
                            putBoolean("is_local", true)
                        }
                        controller?.sendCustomCommand(
                            PlaybackService.SET_VIDEO_MODE_COMMAND,
                            args,
                        )
                    } else {
                        // Resolve from YouTube, preserving language selection
                        val videoStream = repository.resolveVideoUrl(freshEp, currentLanguageCode)
                        if (videoStream != null) {
                            val args = Bundle().apply {
                                putString("video_url", videoStream.videoUrl)
                                putString("audio_url", videoStream.audioUrl)
                                putLong("position_ms", currentPos)
                                putString("title", ep.title)
                                putString("artwork_url", currentArtworkUrl)
                                putBoolean("is_local", false)
                            }
                            controller?.sendCustomCommand(
                                PlaybackService.SET_VIDEO_MODE_COMMAND,
                                args,
                            )
                        } else {
                            isVideoMode = false
                            android.util.Log.w("PlayerManager", "No video stream available for episode")
                        }
                    }
                } catch (e: Exception) {
                    isVideoMode = false
                    android.util.Log.e("PlayerManager", "Failed to switch to video mode", e)
                }
                isChangingMedia = false
                updateState()
            }
        }
    }

    /**
     * Launch the YouTube app at the current playback position.
     * Pauses Podcasto audio playback first to avoid double audio.
     */
    fun launchYouTubeApp() {
        val episode = currentEpisode ?: return
        if (currentSourceType != "youtube") return
        val positionMs = controller?.currentPosition ?: 0L

        // Pause playback in Podcasto
        controller?.pause()

        // Build the YouTube deep link with time parameter
        val videoUrl = episode.audioUrl // This is the YouTube watch URL
        val positionSeconds = (positionMs / 1000).toInt()
        val separator = if (videoUrl.contains("?")) "&" else "?"
        val deepLink = "${videoUrl}${separator}t=${positionSeconds}"

        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "Failed to launch YouTube app", e)
            // Resume playback if launch failed
            controller?.play()
        }
    }

    fun updateState() {
        _playerState.value = PlayerState(
            currentEpisode = currentEpisode,
            isPlaying = controller?.isPlaying == true,
            currentPosition = controller?.currentPosition ?: 0,
            duration = controller?.duration?.takeIf { it > 0 } ?: 0,
            podcastArtworkUrl = currentArtworkUrl,
            podcastSourceType = currentSourceType,
            volumeNormEnabled = volumeNormEnabled,
            isVideoMode = isVideoMode,
        )
    }

    private fun saveCurrentPosition() {
        val ep = currentEpisode ?: return
        val pos = controller?.currentPosition ?: return
        // Don't overwrite a saved position with 0 when the controller has no media loaded
        // (e.g. after app restart, before any playback has started)
        if (pos == 0L && controller?.isPlaying != true && (controller?.duration ?: 0) <= 0) return
        scope.launch {
            repository.updatePlaybackPosition(ep.id, pos)
        }
    }

    private fun markCurrentAsPlayed() {
        val ep = currentEpisode ?: return
        scope.launch {
            repository.markAsPlayed(ep.id)
            repository.removeFromPlaylist(ep.id)
            // Play next episode from playlist if available
            val remaining = repository.getPlaylistEpisodesWithArtworkList()
            if (remaining.isNotEmpty()) {
                val next = remaining.first()
                play(next.episode, next.artworkUrl)
            } else {
                currentEpisode = null
                updateState()
            }
        }
    }

    fun release() {
        saveCurrentPosition()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    fun getController(): MediaController? = controller
}
