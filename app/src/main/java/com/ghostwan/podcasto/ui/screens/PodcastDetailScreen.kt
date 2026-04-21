package com.ghostwan.podcasto.ui.screens

import com.ghostwan.podcasto.BuildConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.local.EpisodeEntity
import com.ghostwan.podcasto.data.local.PodcastEntity
import com.ghostwan.podcasto.data.local.TagEntity
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playerManager: PlayerManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val podcastId: Long = savedStateHandle.get<Long>("podcastId") ?: 0
    private val feedUrl: String = savedStateHandle.get<String>("feedUrl") ?: ""
    private val artworkUrl: String = savedStateHandle.get<String>("artworkUrl") ?: ""
    private val collectionName: String = savedStateHandle.get<String>("collectionName") ?: ""
    private val artistName: String = savedStateHandle.get<String>("artistName") ?: ""

    private val _podcast = MutableStateFlow<PodcastEntity?>(null)
    val podcast: StateFlow<PodcastEntity?> = _podcast.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeEntity>>(emptyList())
    val episodes: StateFlow<List<EpisodeEntity>> = _episodes.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _hidePlayedEpisodes = MutableStateFlow(false)
    val hidePlayedEpisodes: StateFlow<Boolean> = _hidePlayedEpisodes.asStateFlow()

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val podcastTags: StateFlow<List<TagEntity>> = repository.getTagsForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showTagDialog = MutableStateFlow(false)
    val showTagDialog: StateFlow<Boolean> = _showTagDialog.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val nowPlayingEpisodeId: StateFlow<Long?> = playerManager.playerState
        .map { it.currentEpisode?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playlistEpisodeIds: StateFlow<Set<Long>> = repository.getPlaylistEpisodes()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun playEpisode(episode: EpisodeEntity) {
        val artwork = _podcast.value?.artworkUrl ?: artworkUrl
        playerManager.play(episode, artwork)
    }

    private var episodesJob: Job? = null

    init {
        loadPodcast()
    }

    private fun loadPodcast() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val existing = repository.getPodcastById(podcastId)
                if (existing != null) {
                    _podcast.value = existing
                    _isSubscribed.value = existing.subscribed
                    launch {
                        repository.refreshPodcastEpisodes(existing)
                    }
                    collectEpisodes()
                } else if (feedUrl.contains("youtube.com/feeds/videos.xml")) {
                    // YouTube channel preview
                    val (pod, eps) = repository.fetchYouTubePreviewFromFeed(
                        feedUrl, podcastId, artworkUrl, collectionName, artistName
                    )
                    _podcast.value = pod
                    _episodes.value = eps
                    _isSubscribed.value = false
                    _isLoading.value = false
                } else {
                    // feedUrl may be empty for some podcasts (e.g. Radio France);
                    // fetchPodcastPreview will use ApplePodcastsScraper as fallback
                    val (pod, eps) = repository.fetchPodcastPreview(
                        feedUrl, podcastId, artworkUrl, collectionName, artistName
                    )
                    _podcast.value = pod
                    _episodes.value = eps
                    _isSubscribed.value = false
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Unable to load podcast feed"
                _isLoading.value = false
            }
        }
    }

    private fun collectEpisodes() {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            val flow = if (_hidePlayedEpisodes.value) {
                repository.getUnplayedEpisodesForPodcast(podcastId)
            } else {
                repository.getEpisodesForPodcast(podcastId)
            }
            flow.collect {
                _episodes.value = it
                _isLoading.value = false
            }
        }
    }

    fun toggleHidePlayed() {
        _hidePlayedEpisodes.value = !_hidePlayedEpisodes.value
        collectEpisodes()
    }

    fun refreshPodcast() {
        viewModelScope.launch {
            val pod = _podcast.value ?: return@launch
            _isRefreshing.value = true
            try {
                repository.refreshPodcastEpisodes(pod)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _isRefreshing.value = false
        }
    }

    fun toggleSubscription() {
        viewModelScope.launch {
            val pod = _podcast.value ?: return@launch
            if (_isSubscribed.value) {
                repository.unsubscribe(podcastId)
                _isSubscribed.value = false
            } else {
                repository.subscribeToPodcastFromDetail(pod, _episodes.value)
                _isSubscribed.value = true
            }
        }
    }

    fun showTagDialog() {
        _showTagDialog.value = true
    }

    fun hideTagDialog() {
        _showTagDialog.value = false
    }

    fun toggleTag(tag: TagEntity, isCurrentlyAssigned: Boolean) {
        viewModelScope.launch {
            if (isCurrentlyAssigned) {
                repository.removeTagFromPodcast(podcastId, tag.id)
            } else {
                repository.addTagToPodcast(podcastId, tag.id)
            }
        }
    }

    fun createAndAssignTag(name: String) {
        viewModelScope.launch {
            val tagId = repository.createTag(name)
            repository.addTagToPodcast(podcastId, tagId)
        }
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun PodcastDetailScreen(
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onTagClick: (Long) -> Unit = {},
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSubscribed by viewModel.isSubscribed.collectAsState()
    val showTagDialog by viewModel.showTagDialog.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val podcastTags by viewModel.podcastTags.collectAsState()
    val hidePlayed by viewModel.hidePlayedEpisodes.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val nowPlayingId by viewModel.nowPlayingEpisodeId.collectAsState()
    val playlistIds by viewModel.playlistEpisodeIds.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    if (showTagDialog) {
        TagManagementDialog(
            allTags = allTags,
            assignedTags = podcastTags,
            onToggleTag = viewModel::toggleTag,
            onCreateTag = viewModel::createAndAssignTag,
            onDismiss = viewModel::hideTagDialog,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(podcast?.title ?: stringResource(R.string.podcast_fallback), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                // Filter played/unplayed toggle
                IconButton(onClick = viewModel::toggleHidePlayed) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.filter_played),
                        tint = if (hidePlayed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSubscribed) {
                    IconButton(onClick = viewModel::showTagDialog) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.tags))
                    }
                }
            },
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            val pullRefreshState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = { viewModel.refreshPodcast() },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState),
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                // Header
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box {
                            AsyncImage(
                                model = podcast?.artworkUrl,
                                contentDescription = podcast?.title,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            if (BuildConfig.YOUTUBE_ENABLED && podcast?.sourceType == "youtube") {
                                YouTubeBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = podcast?.title ?: "",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = podcast?.author ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = viewModel::toggleSubscription,
                                colors = if (isSubscribed)
                                    ButtonDefaults.outlinedButtonColors()
                                else
                                    ButtonDefaults.buttonColors(),
                            ) {
                                Icon(
                                    if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isSubscribed) stringResource(R.string.subscribed) else stringResource(R.string.subscribe))
                            }
                        }
                    }
                }

                // Tags
                if (podcastTags.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            podcastTags.forEach { tag ->
                                AssistChip(
                                    onClick = { onTagClick(tag.id) },
                                    label = { Text(tag.name, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }

                // Description
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    val desc = podcast?.description ?: ""
                    if (desc.isNotEmpty()) {
                        val linkColor = MaterialTheme.colorScheme.primary
                        val annotatedDesc = remember(desc) { htmlToAnnotatedString(desc, linkColor) }
                        val uriHandler = LocalUriHandler.current
                        ClickableText(
                            text = annotatedDesc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            onClick = { offset ->
                                annotatedDesc.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        try { uriHandler.openUri(annotation.item) } catch (_: Exception) {}
                                    }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.episodes_count, episodes.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (hidePlayed) {
                            Text(
                                text = stringResource(R.string.unplayed_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Episodes
                items(episodes) { episode ->
                    val isInPlaylist = episode.id in playlistIds
                    EpisodeListItem(
                        episode = episode,
                        isInPlaylist = isInPlaylist,
                        isNowPlaying = episode.id == nowPlayingId,
                        onTogglePlaylist = { viewModel.togglePlaylist(episode.id) },
                        onClick = { onEpisodeClick(episode.id) },
                        onLongClick = { viewModel.playEpisode(episode) },
                    )
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeListItem(
    episode: EpisodeEntity,
    isInPlaylist: Boolean = false,
    isNowPlaying: Boolean = false,
    onTogglePlaylist: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        // Background revealed during swipe
        if (onTogglePlaylist != null && offsetX.value != 0f) {
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .then(
                    if (onTogglePlaylist != null) {
                        Modifier.pointerInput(isInPlaylist) {
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
                        }
                    } else Modifier
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            colors = if (isNowPlaying) CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) else CardDefaults.cardColors(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNowPlaying) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = stringResource(R.string.now_playing),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = episode.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (episode.played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val formattedDate = formatPubDate(episode.pubDateTimestamp, episode.pubDate)
                            if (formattedDate.isNotEmpty()) {
                                Text(
                                    text = formattedDate,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (episode.duration > 0) {
                                if (formattedDate.isNotEmpty()) {
                                    Text(
                                        text = " - ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = formatDuration(episode.duration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (episode.played) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.played),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (isInPlaylist) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = stringResource(R.string.in_playlist),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (episode.downloadPath != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.DownloadDone,
                                    contentDescription = stringResource(R.string.downloaded),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                // Playback progress bar
                if (!episode.played && episode.playbackPosition > 0 && episode.duration > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (episode.playbackPosition.toFloat() / (episode.duration * 1000)).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (isNowPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    )
                }
            }
        }
    }
}

@Composable
fun TagManagementDialog(
    allTags: List<TagEntity>,
    assignedTags: List<TagEntity>,
    onToggleTag: (TagEntity, Boolean) -> Unit,
    onCreateTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTagName by remember { mutableStateOf("") }
    val assignedTagIds = assignedTags.map { it.id }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manage_tags)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text(stringResource(R.string.new_tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (newTagName.isNotBlank()) {
                            IconButton(onClick = {
                                onCreateTag(newTagName.trim())
                                newTagName = ""
                            }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                allTags.forEach { tag ->
                    val isAssigned = tag.id in assignedTagIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleTag(tag, isAssigned) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isAssigned,
                            onCheckedChange = { onToggleTag(tag, isAssigned) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tag.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%d:%02d", m, s)
    }
}

fun formatPubDate(pubDateTimestamp: Long, pubDateFallback: String): String {
    if (pubDateTimestamp <= 0) return pubDateFallback
    val date = java.util.Date(pubDateTimestamp)
    val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
    return format.format(date)
}

/**
 * YouTube badge overlay for artwork — small red ribbon in the bottom-end corner.
 * Call this inside a Box that wraps the artwork image.
 */
@Composable
fun YouTubeBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = androidx.compose.ui.graphics.Color(0xFFFF0000),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = "YT",
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
