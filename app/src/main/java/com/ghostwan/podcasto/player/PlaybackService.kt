package com.ghostwan.podcasto.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ghostwan.podcasto.MainActivity
import com.ghostwan.podcasto.R

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    companion object {
        private val SEEK_BACKWARD_COMMAND = SessionCommand("SEEK_BACKWARD_10", android.os.Bundle.EMPTY)
        private val SEEK_FORWARD_COMMAND = SessionCommand("SEEK_FORWARD_30", android.os.Bundle.EMPTY)
        private val VOLUME_NORM_TOGGLE_COMMAND = SessionCommand("VOLUME_NORM_TOGGLE", android.os.Bundle.EMPTY)
        val SET_VIDEO_MODE_COMMAND = SessionCommand("SET_VIDEO_MODE", android.os.Bundle.EMPTY)
    }

    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()
        exoPlayer = player

        // Attach LoudnessEnhancer after player is created
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("OPEN_PLAYER", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val backwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName(getString(R.string.rewind_10s))
            .setSessionCommand(SEEK_BACKWARD_COMMAND)
            .build()

        val forwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName(getString(R.string.forward_30s))
            .setSessionCommand(SEEK_FORWARD_COMMAND)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setCustomLayout(listOf(backwardButton, forwardButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SEEK_BACKWARD_COMMAND)
                        .add(SEEK_FORWARD_COMMAND)
                        .add(VOLUME_NORM_TOGGLE_COMMAND)
                        .add(SET_VIDEO_MODE_COMMAND)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: android.os.Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        "SEEK_FORWARD_30" -> {
                            val p = session.player
                            p.seekTo(minOf(p.currentPosition + 30_000, p.duration))
                        }
                        "SEEK_BACKWARD_10" -> {
                            val p = session.player
                            p.seekTo(maxOf(p.currentPosition - 10_000, 0))
                        }
                        "VOLUME_NORM_TOGGLE" -> {
                            val enabled = args.getBoolean("enabled", false)
                            setVolumeNormalization(enabled)
                        }
                        "SET_VIDEO_MODE" -> {
                            val videoUrl = args.getString("video_url")
                            val audioUrl = args.getString("audio_url")
                            val positionMs = args.getLong("position_ms", 0)
                            val title = args.getString("title", "")
                            val artworkUrl = args.getString("artwork_url", "")
                            val isLocal = args.getBoolean("is_local", false)
                            if (videoUrl != null && audioUrl != null) {
                                setVideoMode(videoUrl, audioUrl, positionMs, title, artworkUrl, isLocal)
                            }
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                // Read saved preference
                val prefs = getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                val normEnabled = prefs.getBoolean("volume_normalization", false)
                setTargetGain(600) // +6dB target gain for speech normalization
                enabled = normEnabled
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loudnessEnhancer = null
        }
    }

    private fun setVolumeNormalization(enabled: Boolean) {
        try {
            loudnessEnhancer?.enabled = enabled
            getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("volume_normalization", enabled)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Switch to video mode by creating a MergingMediaSource with separate video-only
     * and audio-only streams. Uses DefaultDataSource.Factory for local files (file:// URIs)
     * and DefaultHttpDataSource.Factory for remote URLs.
     */
    private fun setVideoMode(videoUrl: String, audioUrl: String, positionMs: Long, title: String, artworkUrl: String, isLocal: Boolean = false) {
        val player = exoPlayer ?: return
        val dataSourceFactory = if (isLocal) {
            DefaultDataSource.Factory(this)
        } else {
            DefaultHttpDataSource.Factory()
        }
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(Uri.parse(videoUrl))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(title)
                            .setArtworkUri(if (artworkUrl.isNotEmpty()) Uri.parse(artworkUrl) else null)
                            .build()
                    )
                    .build()
            )
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
        val mergedSource = MergingMediaSource(videoSource, audioSource)
        player.setMediaSource(mergedSource, positionMs)
        player.prepare()
        player.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}
