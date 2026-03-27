package com.ghostwan.podcasto.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.local.EpisodeWithArtwork
import com.ghostwan.podcasto.data.local.TagEntity
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import com.ghostwan.podcasto.player.PlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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

    val hiddenPodcastIds: StateFlow<Set<Long>> = repository.getHiddenPodcastIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nowPlayingEpisodeId: StateFlow<Long?> = playerManager.playerState
        .map { it.currentEpisode?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playerState = playerManager.playerState

    private val _isAutoAdding = MutableStateFlow(false)
    val isAutoAdding: StateFlow<Boolean> = _isAutoAdding.asStateFlow()

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
            _isAutoAdding.value = true
            repository.autoAddLatestEpisodes()
            _isAutoAdding.value = false
        }
    }

    fun autoAddLatestForTag(tagId: Long) {
        viewModelScope.launch {
            _isAutoAdding.value = true
            repository.autoAddLatestEpisodesForTag(tagId)
            _isAutoAdding.value = false
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
    showHidden: Boolean = false,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val dbEpisodes by viewModel.episodes.collectAsState()
    val hiddenIds by viewModel.hiddenPodcastIds.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val nowPlayingId by viewModel.nowPlayingEpisodeId.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val isAutoAdding by viewModel.isAutoAdding.collectAsState()

    // Filter hidden podcasts unless showHidden is enabled
    val filteredEpisodes = if (showHidden) dbEpisodes else dbEpisodes.filter { it.episode.podcastId !in hiddenIds }

    // Local mutable copy for reordering
    var localEpisodes by remember { mutableStateOf(filteredEpisodes) }
    // Sync from DB when DB changes and user is NOT dragging
    LaunchedEffect(filteredEpisodes) {
        localEpisodes = filteredEpisodes
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
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(R.string.nav_playlist)) },
            actions = {
                IconButton(onClick = { showAutoAddDialog = true }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.auto_add_episodes))
                }
                if (localEpisodes.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearPlaylist) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.clear_playlist))
                    }
                }
            },
        )

        Box(modifier = Modifier.fillMaxSize()) {
        if (localEpisodes.isEmpty() && !isAutoAdding) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.playlist_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (!isAutoAdding || localEpisodes.isNotEmpty()) {
            Column {
            // Play all button
            if (localEpisodes.isNotEmpty()) {
            Button(
                onClick = viewModel::playAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.play_all))
            }
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
                            isNowPlaying = item.episode.id == nowPlayingId,
                            livePosition = if (item.episode.id == nowPlayingId) playerState.currentPosition else null,
                            liveDuration = if (item.episode.id == nowPlayingId) playerState.duration else null,
                            onClick = { onEpisodeClick(item.episode.id) },
                            onLongClick = { viewModel.playEpisode(item) },
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
            } // end Column
        }

        // Loading overlay for auto-add
        if (isAutoAdding) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        } // end Box
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistEpisodeItem(
    index: Int,
    item: EpisodeWithArtwork,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    isNowPlaying: Boolean = false,
    livePosition: Long? = null,
    liveDuration: Long? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        colors = if (isNowPlaying) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle — long press here only triggers drag
            Icon(
                Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragModifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Content area — tap = detail, long press = play
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (isNowPlaying) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = stringResource(R.string.now_playing),
                    modifier = Modifier.width(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
            }
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
                // Progress bar: use live position for now-playing, DB position for others
                val position = livePosition ?: item.episode.playbackPosition
                val duration = liveDuration ?: (item.episode.duration * 1000L)
                val showProgress = if (isNowPlaying) duration > 0 && position > 0
                    else !item.episode.played && item.episode.playbackPosition > 0 && item.episode.duration > 0
                if (showProgress && duration > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (isNowPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    )
                }
            }
            } // end combinedClickable Row
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.remove),
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
        title = { Text(stringResource(R.string.auto_add_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.auto_add_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // From all subscriptions
                TextButton(
                    onClick = onAddFromAll,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.from_all_subscriptions))
                }

                // By tag
                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.or_filter_by_tag),
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
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
