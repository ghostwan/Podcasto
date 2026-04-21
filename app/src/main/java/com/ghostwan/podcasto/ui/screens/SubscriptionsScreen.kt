package com.ghostwan.podcasto.ui.screens

import com.ghostwan.podcasto.BuildConfig
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.local.PodcastEntity
import com.ghostwan.podcasto.data.local.TagEntity
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.web.WebServerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import javax.inject.Inject

enum class PodcastSortOrder {
    ALPHABETICAL,
    SUBSCRIPTION_DATE,
    LATEST_EPISODE,
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {

    val subscribedPodcasts: StateFlow<List<PodcastEntity>> = repository.getSubscribedPodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val latestTimestamps: StateFlow<Map<Long, Long>> = repository.getLatestEpisodeTimestampPerPodcast()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _selectedTagId = MutableStateFlow<Long?>(null)
    val selectedTagId: StateFlow<Long?> = _selectedTagId.asStateFlow()

    private val _filteredPodcasts = MutableStateFlow<List<PodcastEntity>?>(null)
    val filteredPodcasts: StateFlow<List<PodcastEntity>?> = _filteredPodcasts.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _sortOrder = MutableStateFlow(PodcastSortOrder.ALPHABETICAL)
    val sortOrder: StateFlow<PodcastSortOrder> = _sortOrder.asStateFlow()

    private var tagFilterJob: kotlinx.coroutines.Job? = null

    fun setSortOrder(order: PodcastSortOrder) {
        _sortOrder.value = order
    }

    fun toggleHidden(podcastId: Long, currentlyHidden: Boolean) {
        viewModelScope.launch {
            repository.setHidden(podcastId, !currentlyHidden)
        }
    }

    fun selectTag(tagId: Long?) {
        _selectedTagId.value = tagId
        tagFilterJob?.cancel()
        if (tagId == null) {
            _filteredPodcasts.value = null
        } else {
            tagFilterJob = viewModelScope.launch {
                repository.getPodcastsForTag(tagId).collect {
                    _filteredPodcasts.value = it
                }
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val podcasts = subscribedPodcasts.value
            podcasts.forEach { podcast ->
                try {
                    repository.refreshPodcastEpisodes(podcast)
                } catch (_: Exception) {}
            }
            _isRefreshing.value = false
        }
    }

    fun saveTagOrder(orderedTagIds: List<Long>) {
        viewModelScope.launch {
            repository.updateTagPositions(orderedTagIds)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun SubscriptionsScreen(
    onPodcastClick: (Long) -> Unit,
    onDiscoverClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    pendingTagId: Long? = null,
    onPendingTagConsumed: () -> Unit = {},
    showHidden: Boolean = false,
    onToggleShowHidden: () -> Unit = {},
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val allPodcasts by viewModel.subscribedPodcasts.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val selectedTagId by viewModel.selectedTagId.collectAsState()
    val filteredPodcasts by viewModel.filteredPodcasts.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val latestTimestamps by viewModel.latestTimestamps.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val threeMonthsAgo = remember { System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000 }

    var showTagReorderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Apply tag filter when navigating from PodcastDetailScreen
    LaunchedEffect(pendingTagId) {
        if (pendingTagId != null) {
            viewModel.selectTag(pendingTagId)
            onPendingTagConsumed()
        }
    }

    // Filter: apply tag filter, then hide hidden podcasts unless showHidden is enabled
    val tagFiltered = filteredPodcasts ?: allPodcasts
    val visiblePodcasts = if (showHidden) tagFiltered else tagFiltered.filter { !it.hidden }
    // Sort
    val displayPodcasts = remember(visiblePodcasts, sortOrder, latestTimestamps) {
        when (sortOrder) {
            PodcastSortOrder.ALPHABETICAL -> visiblePodcasts.sortedBy { it.title.lowercase() }
            PodcastSortOrder.SUBSCRIPTION_DATE -> visiblePodcasts.sortedByDescending { it.subscribedAt }
            PodcastSortOrder.LATEST_EPISODE -> visiblePodcasts.sortedByDescending { latestTimestamps[it.id] ?: 0L }
        }
    }
    val hiddenCount = tagFiltered.count { it.hidden }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val webServerRunning by WebServerService.isRunning.collectAsState()
    val webServerUrl by WebServerService.serverUrl.collectAsState()
    val tunnelUrlState by WebServerService.tunnelUrl.collectAsState()
    val isTunnelConnecting by WebServerService.isTunnelConnecting.collectAsState()
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showServerModeDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    if (!webServerRunning) {
                        Text(stringResource(R.string.nav_subscriptions))
                    }
                },
                actions = {
                    // Display the active URL (tunnel URL takes priority over local)
                    val displayUrl = tunnelUrlState ?: webServerUrl
                    if (webServerRunning && displayUrl != null) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(displayUrl))
                            },
                        ) {
                            Text(
                                text = displayUrl,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // Web server toggle — single button, shows mode dialog when starting
                    IconButton(
                        onClick = {
                            if (webServerRunning) {
                                WebServerService.stop(context)
                            } else {
                                showServerModeDialog = true
                            }
                        },
                        enabled = !isTunnelConnecting,
                    ) {
                        if (isTunnelConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(
                                    if (webServerRunning) R.drawable.ic_web_on else R.drawable.ic_web_off,
                                ),
                                contentDescription = if (webServerRunning) stringResource(R.string.web_server_stop) else stringResource(R.string.web_server_start),
                                tint = if (webServerRunning) {
                                    if (tunnelUrlState != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Sort menu
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_podcasts))
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_alphabetical)) },
                                onClick = { viewModel.setSortOrder(PodcastSortOrder.ALPHABETICAL); showSortMenu = false },
                                leadingIcon = { Icon(Icons.Default.SortByAlpha, contentDescription = null) },
                                trailingIcon = { if (sortOrder == PodcastSortOrder.ALPHABETICAL) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_subscription_date)) },
                                onClick = { viewModel.setSortOrder(PodcastSortOrder.SUBSCRIPTION_DATE); showSortMenu = false },
                                leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                trailingIcon = { if (sortOrder == PodcastSortOrder.SUBSCRIPTION_DATE) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_latest_episode)) },
                                onClick = { viewModel.setSortOrder(PodcastSortOrder.LATEST_EPISODE); showSortMenu = false },
                                leadingIcon = { Icon(Icons.Default.NewReleases, contentDescription = null) },
                                trailingIcon = { if (sortOrder == PodcastSortOrder.LATEST_EPISODE) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                    // Show/hide hidden podcasts toggle
                    if (hiddenCount > 0) {
                        IconButton(onClick = { onToggleShowHidden() }) {
                            Icon(
                                imageVector = if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = stringResource(R.string.show_hidden_podcasts),
                                tint = if (showHidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onSettingsClick()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onDiscoverClick,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.discover_podcasts))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

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
                        modifier = Modifier.combinedClickable(
                            onClick = { viewModel.selectTag(tag.id) },
                            onLongClick = { showTagReorderDialog = true },
                        ),
                    )
                }
            }
        }

        if (showTagReorderDialog) {
            TagReorderDialog(
                tags = allTags,
                onSave = { orderedIds ->
                    viewModel.saveTagOrder(orderedIds)
                    showTagReorderDialog = false
                },
                onDismiss = { showTagReorderDialog = false },
            )
        }

        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = { viewModel.refreshAll() },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pullRefresh(pullRefreshState),
        ) {
        if (displayPodcasts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selectedTagId != null) stringResource(R.string.no_podcasts_with_tag) else stringResource(R.string.no_subscriptions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(displayPodcasts, key = { it.id }) { podcast ->
                    val latestTs = latestTimestamps[podcast.id] ?: 0L
                    val isStale = latestTs in 1..threeMonthsAgo
                    SubscriptionItem(
                        podcast = podcast,
                        isStale = isStale,
                        showHiddenIndicator = showHidden && podcast.hidden,
                        onClick = { onPodcastClick(podcast.id) },
                        onLongClick = { viewModel.toggleHidden(podcast.id, podcast.hidden) },
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

    // Server mode dialog
    if (showServerModeDialog) {
        AlertDialog(
            onDismissRequest = { showServerModeDialog = false },
            title = { Text(stringResource(R.string.web_server_mode_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Local option
                    OutlinedCard(
                        onClick = {
                            showServerModeDialog = false
                            WebServerService.start(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_web_on),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column {
                                Text(
                                    stringResource(R.string.web_server_mode_local),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.web_server_mode_local_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Tunnel option
                    OutlinedCard(
                        onClick = {
                            showServerModeDialog = false
                            WebServerService.startWithTunnel(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Column {
                                Text(
                                    stringResource(R.string.web_server_mode_tunnel),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.web_server_mode_tunnel_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showServerModeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubscriptionItem(
    podcast: PodcastEntity,
    isStale: Boolean = false,
    showHiddenIndicator: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showHiddenIndicator) Modifier.alpha(0.45f) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = if (isStale) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        ) else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AsyncImage(
                    model = podcast.artworkUrl,
                    contentDescription = podcast.title,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (BuildConfig.YOUTUBE_ENABLED && podcast.sourceType == "youtube") {
                    YouTubeBadge(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = podcast.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showHiddenIndicator) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.podcast_hidden),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TagReorderDialog(
    tags: List<TagEntity>,
    onSave: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var localTags by remember { mutableStateOf(tags) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localTags = localTags.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reorder_tags)) },
        text = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(localTags, key = { it.id }) { tag ->
                    ReorderableItem(reorderableState, key = tag.id) { isDragging ->
                        val elevation = if (isDragging) 8.dp else 0.dp
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.DragHandle,
                                    contentDescription = stringResource(R.string.reorder),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .longPressDraggableHandle()
                                        .size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(localTags.map { it.id }) }) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
