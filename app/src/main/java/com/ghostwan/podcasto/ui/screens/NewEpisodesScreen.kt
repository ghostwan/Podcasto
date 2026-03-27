package com.ghostwan.podcasto.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewEpisodesViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playerManager: PlayerManager,
) : ViewModel() {

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTagId = MutableStateFlow<Long?>(null)
    val selectedTagId: StateFlow<Long?> = _selectedTagId.asStateFlow()

    private val _hidePlayed = MutableStateFlow(false)
    val hidePlayed: StateFlow<Boolean> = _hidePlayed.asStateFlow()

    val episodes: StateFlow<List<EpisodeWithArtwork>> = combine(_selectedTagId, _hidePlayed) { tagId, hide ->
        Pair(tagId, hide)
    }.flatMapLatest { (tagId, hide) ->
        val flow = if (tagId == null) {
            repository.getRecentEpisodesWithArtwork()
        } else {
            repository.getRecentEpisodesWithArtworkForTag(tagId)
        }
        if (hide) {
            flow.map { list -> list.filter { !it.episode.played } }
        } else {
            flow
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlistEpisodeIds: StateFlow<Set<Long>> = repository.getPlaylistEpisodes()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val nowPlayingEpisodeId: StateFlow<Long?> = playerManager.playerState
        .map { it.currentEpisode?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun selectTag(tagId: Long?) {
        _selectedTagId.value = tagId
    }

    fun toggleHidePlayed() {
        _hidePlayed.value = !_hidePlayed.value
    }

    fun togglePlaylist(episodeId: Long) {
        viewModelScope.launch {
            if (repository.isInPlaylist(episodeId)) {
                repository.removeFromPlaylist(episodeId)
            } else {
                repository.addToPlaylist(episodeId)
            }
        }
    }

    fun playEpisode(item: EpisodeWithArtwork) {
        playerManager.play(item.episode, item.artworkUrl)
    }

    fun refreshEpisodes() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val tagId = _selectedTagId.value
            val podcasts = if (tagId == null) {
                repository.getSubscribedPodcasts().first()
            } else {
                repository.getPodcastsForTag(tagId).first()
            }
            podcasts.forEach { podcast ->
                try {
                    repository.refreshPodcastEpisodes(podcast)
                } catch (_: Exception) {}
            }
            _isRefreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun NewEpisodesScreen(
    onEpisodeClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    viewModel: NewEpisodesViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsState()
    val playlistIds by viewModel.playlistEpisodeIds.collectAsState()
    val nowPlayingId by viewModel.nowPlayingEpisodeId.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val selectedTagId by viewModel.selectedTagId.collectAsState()
    val hidePlayed by viewModel.hidePlayed.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refreshEpisodes() },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(R.string.nav_new_episodes)) },
            actions = {
                IconButton(onClick = viewModel::toggleHidePlayed) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.filter_played),
                        tint = if (hidePlayed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onHistoryClick) {
                    Icon(Icons.Default.History, contentDescription = stringResource(R.string.nav_history))
                }
            },
        )

        // Tag filter chips
        if (allTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedTagId == null,
                    onClick = { viewModel.selectTag(null) },
                    label = { Text(stringResource(R.string.all)) },
                )
                allTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTagId == tag.id,
                        onClick = { viewModel.selectTag(tag.id) },
                        label = { Text(tag.name) },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState),
        ) {
            if (episodes.isEmpty() && !isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FiberNew,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (selectedTagId != null) stringResource(R.string.no_episodes_for_tag)
                            else stringResource(R.string.no_new_episodes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(episodes, key = { it.episode.id }) { item ->
                        val isInPlaylist = item.episode.id in playlistIds
                        val isNowPlaying = item.episode.id == nowPlayingId

                        NewEpisodeRow(
                            item = item,
                            isInPlaylist = isInPlaylist,
                            isNowPlaying = isNowPlaying,
                            onTogglePlaylist = { viewModel.togglePlaylist(item.episode.id) },
                            onClick = { onEpisodeClick(item.episode.id) },
                            onLongClick = { viewModel.playEpisode(item) },
                        )
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewEpisodeRow(
    item: EpisodeWithArtwork,
    isInPlaylist: Boolean,
    isNowPlaying: Boolean,
    onTogglePlaylist: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dateFormat = remember {
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
    }
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Background revealed during swipe
        if (offsetX.value != 0f) {
            val swipingRight = offsetX.value > 0
            Surface(
                modifier = Modifier.matchParentSize(),
                color = if (isInPlaylist) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = if (swipingRight) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Icon(
                        if (isInPlaylist) Icons.AutoMirrored.Filled.QueueMusic
                        else Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        tint = if (isInPlaylist) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(isInPlaylist) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = swipeThresholdPx
                            if (kotlin.math.abs(offsetX.value) >= threshold) {
                                onTogglePlaylist()
                            }
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coroutineScope.launch {
                                val newValue = offsetX.value + dragAmount
                                offsetX.snapTo(newValue.coerceIn(-swipeThresholdPx * 1.2f, swipeThresholdPx * 1.2f))
                            }
                        },
                    )
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            ListItem(
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
                leadingContent = {
                    Box {
                        AsyncImage(
                            model = item.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        if (isNowPlaying) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.now_playing),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                headlineContent = {
                    Text(
                        text = item.episode.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = if (item.episode.pubDateTimestamp > 0)
                                dateFormat.format(Date(item.episode.pubDateTimestamp))
                            else item.episode.pubDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.episode.duration > 0) {
                            Text(
                                text = formatDuration(item.episode.duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isInPlaylist) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = stringResource(R.string.in_playlist),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (item.episode.played) {
                            if (isInPlaylist) Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.played),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}
