package com.music.podcasto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.music.podcasto.player.PlayerManager
import com.music.podcasto.player.PlayerState
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(
    playerState: PlayerState,
    playerManager: PlayerManager,
    onExpand: () -> Unit,
) {
    if (playerState.currentEpisode == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = playerState.podcastArtworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playerState.currentEpisode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playerState.duration > 0) {
                    LinearProgressIndicator(
                        progress = { (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { playerManager.togglePlayPause() }) {
                Icon(
                    if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerManager: PlayerManager,
    onBack: () -> Unit,
) {
    val playerState by playerManager.playerState.collectAsState()
    val episode = playerState.currentEpisode

    // Periodically update position
    LaunchedEffect(playerState.isPlaying) {
        while (playerState.isPlaying) {
            delay(500)
            playerManager.updateState()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Now Playing") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (episode == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nothing playing", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Artwork
                AsyncImage(
                    model = playerState.podcastArtworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Title
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Progress
                val progress = if (playerState.duration > 0) {
                    (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        if (playerState.duration > 0) {
                            playerManager.seekTo((newValue * playerState.duration).toLong())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(playerState.currentPosition / 1000),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatDuration(playerState.duration / 1000),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { playerManager.skipBackward() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    FilledIconButton(
                        onClick = { playerManager.togglePlayPause() },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    IconButton(
                        onClick = { playerManager.skipForward() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Default.Forward30,
                            contentDescription = "Forward 30s",
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}
