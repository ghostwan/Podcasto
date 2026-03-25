package com.music.podcasto.player

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.podcasto.R

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    companion object {
        private val SEEK_BACKWARD_COMMAND = SessionCommand("SEEK_BACKWARD_10", android.os.Bundle.EMPTY)
        private val SEEK_FORWARD_COMMAND = SessionCommand("SEEK_FORWARD_30", android.os.Bundle.EMPTY)
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()

        val backwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName(getString(R.string.rewind_10s))
            .setSessionCommand(SEEK_BACKWARD_COMMAND)
            .build()

        val forwardButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName(getString(R.string.forward_30s))
            .setSessionCommand(SEEK_FORWARD_COMMAND)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCustomLayout(listOf(backwardButton, forwardButton))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SEEK_BACKWARD_COMMAND)
                        .add(SEEK_FORWARD_COMMAND)
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
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
