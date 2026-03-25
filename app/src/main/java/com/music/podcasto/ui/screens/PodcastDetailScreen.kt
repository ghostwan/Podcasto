package com.music.podcasto.ui.screens

import android.text.Html
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.music.podcasto.data.local.EpisodeEntity
import com.music.podcasto.data.local.PodcastEntity
import com.music.podcasto.data.local.TagEntity
import com.music.podcasto.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
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

    val allTags: StateFlow<List<TagEntity>> = repository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val podcastTags: StateFlow<List<TagEntity>> = repository.getTagsForPodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showTagDialog = MutableStateFlow(false)
    val showTagDialog: StateFlow<Boolean> = _showTagDialog.asStateFlow()

    init {
        loadPodcast()
    }

    private fun loadPodcast() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Check if already in DB
                val existing = repository.getPodcastById(podcastId)
                if (existing != null) {
                    _podcast.value = existing
                    _isSubscribed.value = existing.subscribed
                    // Launch a refresh in background to ensure episodes are loaded
                    launch {
                        repository.refreshPodcastEpisodes(existing)
                    }
                    repository.getEpisodesForPodcast(podcastId).collect {
                        _episodes.value = it
                        _isLoading.value = false
                    }
                } else if (feedUrl.isNotEmpty()) {
                    val (pod, eps) = repository.fetchPodcastPreview(
                        feedUrl, podcastId, artworkUrl, collectionName, artistName
                    )
                    _podcast.value = pod
                    _episodes.value = eps
                    _isSubscribed.value = false
                    _isLoading.value = false
                } else {
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isLoading.value = false
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSubscribed by viewModel.isSubscribed.collectAsState()
    val showTagDialog by viewModel.showTagDialog.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val podcastTags by viewModel.podcastTags.collectAsState()

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
            title = { Text(podcast?.title ?: "Podcast", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (isSubscribed) {
                    IconButton(onClick = viewModel::showTagDialog) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Tags")
                    }
                }
            },
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                // Header
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = podcast?.artworkUrl,
                            contentDescription = podcast?.title,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
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
                                Text(if (isSubscribed) "Subscribed" else "Subscribe")
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
                                    onClick = {},
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
                    val cleanDesc = Html.fromHtml(desc, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                    if (cleanDesc.isNotEmpty()) {
                        Text(
                            text = cleanDesc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Episodes (${episodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Episodes
                items(episodes) { episode ->
                    EpisodeListItem(
                        episode = episode,
                        onClick = { onEpisodeClick(episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeListItem(
    episode: EpisodeEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.pubDate.isNotEmpty()) {
                Text(
                    text = episode.pubDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.duration > 0) {
                Text(
                    text = formatDuration(episode.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        title = { Text("Manage Tags") },
        text = {
            Column {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("New tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (newTagName.isNotBlank()) {
                            IconButton(onClick = {
                                onCreateTag(newTagName.trim())
                                newTagName = ""
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
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
                Text("Done")
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
