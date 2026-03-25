package com.music.podcasto.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.podcasto.data.local.EpisodeEntity
import com.music.podcasto.data.repository.PodcastRepository
import com.music.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playerManager: PlayerManager,
) : ViewModel() {

    val episodes: StateFlow<List<EpisodeEntity>> = repository.getPlaylistEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playAll() {
        val eps = episodes.value
        if (eps.isNotEmpty()) {
            playerManager.playMultiple(eps)
        }
    }

    fun playEpisode(episode: EpisodeEntity) {
        playerManager.play(episode)
    }

    fun removeFromPlaylist(episodeId: Long) {
        viewModelScope.launch {
            repository.removeFromPlaylist(episodeId)
        }
    }

    fun clearPlaylist() {
        viewModelScope.launch {
            repository.clearPlaylist()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onEpisodeClick: (Long) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Playlist") },
            actions = {
                if (episodes.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearPlaylist) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear playlist")
                    }
                }
            },
        )

        if (episodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Your playlist is empty.\nAdd episodes from their detail page!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Play all button
            Button(
                onClick = viewModel::playAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Play All")
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(episodes) { index, episode ->
                    PlaylistEpisodeItem(
                        index = index + 1,
                        episode = episode,
                        onClick = { onEpisodeClick(episode.id) },
                        onRemove = { viewModel.removeFromPlaylist(episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistEpisodeItem(
    index: Int,
    episode: EpisodeEntity,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.duration > 0) {
                    Text(
                        text = formatDuration(episode.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
