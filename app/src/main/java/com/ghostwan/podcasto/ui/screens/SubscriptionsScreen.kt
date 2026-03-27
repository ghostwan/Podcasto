package com.ghostwan.podcasto.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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
import javax.inject.Inject

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

    private var tagFilterJob: kotlinx.coroutines.Job? = null

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
    val threeMonthsAgo = remember { System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000 }

    // Apply tag filter when navigating from PodcastDetailScreen
    LaunchedEffect(pendingTagId) {
        if (pendingTagId != null) {
            viewModel.selectTag(pendingTagId)
            onPendingTagConsumed()
        }
    }

    // Filter: apply tag filter, then hide hidden podcasts unless showHidden is enabled
    val tagFiltered = filteredPodcasts ?: allPodcasts
    val displayPodcasts = if (showHidden) tagFiltered else tagFiltered.filter { !it.hidden }
    val hiddenCount = tagFiltered.count { it.hidden }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val webServerRunning by WebServerService.isRunning.collectAsState()
    val webServerUrl by WebServerService.serverUrl.collectAsState()
    val tunnelUrlState by WebServerService.tunnelUrl.collectAsState()
    val isTunnelConnecting by WebServerService.isTunnelConnecting.collectAsState()
    var showOverflowMenu by remember { mutableStateOf(false) }

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
                    // Web server toggle
                    IconButton(
                        onClick = {
                            if (webServerRunning) {
                                WebServerService.stop(context)
                            } else {
                                WebServerService.start(context)
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(
                                if (webServerRunning) R.drawable.ic_web_on else R.drawable.ic_web_off,
                            ),
                            contentDescription = if (webServerRunning) stringResource(R.string.web_server_stop) else stringResource(R.string.web_server_start),
                            tint = if (webServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Tunnel toggle (only show when web server is running)
                    if (webServerRunning) {
                        IconButton(
                            onClick = { WebServerService.toggleTunnel(context) },
                            enabled = !isTunnelConnecting,
                        ) {
                            if (isTunnelConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (tunnelUrlState != null) Icons.Default.Cloud else Icons.Default.CloudOff,
                                    contentDescription = stringResource(R.string.tunnel_toggle),
                                    tint = if (tunnelUrlState != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                    )
                }
            }
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
            .then(if (isStale || showHiddenIndicator) Modifier.alpha(0.45f) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = podcast.artworkUrl,
                contentDescription = podcast.title,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
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
