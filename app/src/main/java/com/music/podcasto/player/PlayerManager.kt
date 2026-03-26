package com.music.podcasto.player

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.music.podcasto.data.local.EpisodeEntity
import com.music.podcasto.data.repository.PodcastRepository
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

    private var currentEpisode: EpisodeEntity? = null
    private var currentArtworkUrl: String = ""
    private var positionPollingJob: Job? = null

    private val prefs: SharedPreferences =
        context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    private fun saveLastEpisode(episodeId: Long, artworkUrl: String) {
        prefs.edit()
            .putLong("last_episode_id", episodeId)
            .putString("last_artwork_url", artworkUrl)
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

        // Restore last episode from SharedPreferences (show in mini-player, paused)
        val lastEpisodeId = prefs.getLong("last_episode_id", -1)
        if (lastEpisodeId != -1L && currentEpisode == null) {
            val lastArtwork = prefs.getString("last_artwork_url", "") ?: ""
            scope.launch {
                val episode = repository.getEpisodeById(lastEpisodeId)
                if (episode != null) {
                    currentEpisode = episode
                    currentArtworkUrl = lastArtwork
                    _playerState.value = PlayerState(
                        currentEpisode = episode,
                        isPlaying = false,
                        currentPosition = episode.playbackPosition,
                        duration = 0,
                        podcastArtworkUrl = lastArtwork,
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
                    // Only mark as played if we actually had media loaded and playing
                    if (playbackState == Player.STATE_ENDED && (controller?.duration ?: 0) > 0) {
                        markCurrentAsPlayed()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateState()
                }
            })
            startPositionPolling()
        }, MoreExecutors.directExecutor())
    }

    fun play(episode: EpisodeEntity, artworkUrl: String = "") {
        // Save position of currently playing episode before switching
        saveCurrentPosition()

        currentArtworkUrl = artworkUrl
        saveLastEpisode(episode.id, artworkUrl)

        // Reload episode from DB to get the latest playback position
        scope.launch {
            val freshEpisode = repository.getEpisodeById(episode.id) ?: episode
            currentEpisode = freshEpisode
            val audioUri = if (freshEpisode.downloadPath != null) {
                Uri.parse(freshEpisode.downloadPath)
            } else {
                Uri.parse(freshEpisode.audioUrl)
            }
            val mediaItem = MediaItem.Builder()
                .setUri(audioUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(freshEpisode.title)
                        .setDescription(freshEpisode.description)
                        .setArtworkUri(if (artworkUrl.isNotEmpty()) Uri.parse(artworkUrl) else null)
                        .build()
                )
                .build()
            // Use setMediaItem with startPositionMs so the player seeks after prepare
            val startPos = if (freshEpisode.playbackPosition > 0) freshEpisode.playbackPosition else 0L
            controller?.setMediaItem(mediaItem, startPos)
            controller?.prepare()
            controller?.play()
            updateState()
        }
    }

    fun playMultiple(episodes: List<EpisodeEntity>, startIndex: Int = 0, artworkUrl: String = "") {
        if (episodes.isEmpty()) return
        // Save position of currently playing episode before switching
        saveCurrentPosition()

        currentArtworkUrl = artworkUrl
        saveLastEpisode(episodes[startIndex].id, artworkUrl)

        scope.launch {
            // Reload episodes from DB to get fresh playback positions
            val freshEpisodes = episodes.map { ep ->
                repository.getEpisodeById(ep.id) ?: ep
            }
            currentEpisode = freshEpisodes[startIndex]

            val mediaItems = freshEpisodes.map { episode ->
                val audioUri = if (episode.downloadPath != null) {
                    Uri.parse(episode.downloadPath)
                } else {
                    Uri.parse(episode.audioUrl)
                }
                MediaItem.Builder()
                    .setUri(audioUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(episode.title)
                            .setDescription(episode.description)
                            .build()
                    )
                    .build()
            }
            val startPosition = freshEpisodes[startIndex].playbackPosition
            controller?.setMediaItems(mediaItems, startIndex, startPosition)
            controller?.prepare()
            controller?.play()
            updateState()
        }
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
        controller?.stop()
        currentEpisode = null
        prefs.edit().remove("last_episode_id").remove("last_artwork_url").apply()
        updateState()
    }

    fun getCurrentPositionMs(): Long = controller?.currentPosition ?: 0

    fun updateState() {
        _playerState.value = PlayerState(
            currentEpisode = currentEpisode,
            isPlaying = controller?.isPlaying == true,
            currentPosition = controller?.currentPosition ?: 0,
            duration = controller?.duration?.takeIf { it > 0 } ?: 0,
            podcastArtworkUrl = currentArtworkUrl,
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
