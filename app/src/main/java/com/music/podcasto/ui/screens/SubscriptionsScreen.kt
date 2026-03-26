package com.music.podcasto.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.music.podcasto.R
import com.music.podcasto.data.local.PodcastEntity
import com.music.podcasto.data.local.TagEntity
import com.music.podcasto.data.repository.PodcastRepository
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

    fun selectTag(tagId: Long?) {
        _selectedTagId.value = tagId
        if (tagId == null) {
            _filteredPodcasts.value = null
        } else {
            viewModelScope.launch {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun SubscriptionsScreen(
    onPodcastClick: (Long) -> Unit,
    onDiscoverClick: () -> Unit,
    pendingTagId: Long? = null,
    onPendingTagConsumed: () -> Unit = {},
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

    val displayPodcasts = filteredPodcasts ?: allPodcasts

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_subscriptions)) },
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
                .fillMaxSize()
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
                items(displayPodcasts) { podcast ->
                    val latestTs = latestTimestamps[podcast.id] ?: 0L
                    val isStale = latestTs in 1..threeMonthsAgo
                    SubscriptionItem(
                        podcast = podcast,
                        isStale = isStale,
                        onClick = { onPodcastClick(podcast.id) },
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

@Composable
fun SubscriptionItem(
    podcast: PodcastEntity,
    isStale: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isStale) Modifier.alpha(0.45f) else Modifier)
            .clickable(onClick = onClick),
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
        }
    }
}
