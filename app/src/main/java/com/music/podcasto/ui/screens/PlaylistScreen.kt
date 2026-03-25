package com.music.podcasto.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.music.podcasto.data.local.EpisodeWithArtwork
import com.music.podcasto.data.local.TagEntity
import com.music.podcasto.data.repository.PodcastRepository
import com.music.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playerManager: PlayerManager,
) : ViewModel() {

    val episodes: StateFlow<List<EpisodeWithArtwork>> = repository.getPlaylistEpisodesWithArtwork()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun playAll() {
        val eps = episodes.value.map { it.episode }
        if (eps.isNotEmpty()) {
            playerManager.playMultiple(eps)
        }
    }

    fun playEpisode(item: EpisodeWithArtwork) {
        playerManager.play(item.episode, item.artworkUrl)
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

    fun autoAddLatestFromAll() {
        viewModelScope.launch {
            repository.autoAddLatestEpisodes()
        }
    }

    fun autoAddLatestForTag(tagId: Long) {
        viewModelScope.launch {
            repository.autoAddLatestEpisodesForTag(tagId)
        }
    }

    fun moveItem(fromIndex: Int, toIndex: Int) {
        // This is handled by the UI with a local mutable list
    }

    fun saveOrder(reorderedEpisodes: List<EpisodeWithArtwork>) {
        viewModelScope.launch {
            repository.updatePlaylistPositions(reorderedEpisodes.map { it.episode.id })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    onEpisodeClick: (Long) -> Unit,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val dbEpisodes by viewModel.episodes.collectAsState()
    val allTags by viewModel.allTags.collectAsState()

    // Local mutable copy for reordering
    var localEpisodes by remember { mutableStateOf(dbEpisodes) }
    // Sync from DB when DB changes and user is NOT dragging
    LaunchedEffect(dbEpisodes) {
        localEpisodes = dbEpisodes
    }

    var showAutoAddDialog by remember { mutableStateOf(false) }

    if (showAutoAddDialog) {
        AutoAddDialog(
            tags = allTags,
            onAddFromAll = {
                viewModel.autoAddLatestFromAll()
                showAutoAddDialog = false
            },
            onAddForTag = { tagId ->
                viewModel.autoAddLatestForTag(tagId)
                showAutoAddDialog = false
            },
            onDismiss = { showAutoAddDialog = false },
        )
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localEpisodes = localEpisodes.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Playlist") },
            actions = {
                IconButton(onClick = { showAutoAddDialog = true }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "Auto-add episodes")
                }
                if (localEpisodes.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearPlaylist) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear playlist")
                    }
                }
            },
        )

        if (localEpisodes.isEmpty()) {
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
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(localEpisodes, key = { _, item -> item.episode.id }) { index, item ->
                    ReorderableItem(reorderableLazyListState, key = item.episode.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp
                        PlaylistEpisodeItem(
                            index = index + 1,
                            item = item,
                            elevation = elevation,
                            onClick = { onEpisodeClick(item.episode.id) },
                            onRemove = { viewModel.removeFromPlaylist(item.episode.id) },
                            dragModifier = Modifier.longPressDraggableHandle(
                                onDragStopped = {
                                    viewModel.saveOrder(localEpisodes)
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistEpisodeItem(
    index: Int,
    item: EpisodeWithArtwork,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragModifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$index",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            AsyncImage(
                model = item.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.episode.duration > 0) {
                    Text(
                        text = formatDuration(item.episode.duration),
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

@Composable
fun AutoAddDialog(
    tags: List<TagEntity>,
    onAddFromAll: () -> Unit,
    onAddForTag: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-add Latest Episodes") },
        text = {
            Column {
                Text(
                    text = "Add the latest unplayed episode from each subscription.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // From all subscriptions
                TextButton(
                    onClick = onAddFromAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("From all subscriptions")
                }

                // By tag
                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Or filter by tag:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    tags.forEach { tag ->
                        TextButton(
                            onClick = { onAddForTag(tag.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tag.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
