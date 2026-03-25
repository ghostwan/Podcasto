package com.music.podcasto.ui.screens

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
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
import com.music.podcasto.data.repository.PodcastRepository
import com.music.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodeDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playerManager: PlayerManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val episodeId: Long = savedStateHandle.get<Long>("episodeId") ?: 0

    private val _episode = MutableStateFlow<EpisodeEntity?>(null)
    val episode: StateFlow<EpisodeEntity?> = _episode.asStateFlow()

    private val _podcast = MutableStateFlow<PodcastEntity?>(null)
    val podcast: StateFlow<PodcastEntity?> = _podcast.asStateFlow()

    private val _isInPlaylist = MutableStateFlow(false)
    val isInPlaylist: StateFlow<Boolean> = _isInPlaylist.asStateFlow()

    private val _isDownloaded = MutableStateFlow(false)
    val isDownloaded: StateFlow<Boolean> = _isDownloaded.asStateFlow()

    init {
        loadEpisode()
    }

    private fun loadEpisode() {
        viewModelScope.launch {
            val ep = repository.getEpisodeById(episodeId)
            _episode.value = ep
            if (ep != null) {
                _podcast.value = repository.getPodcastById(ep.podcastId)
                _isInPlaylist.value = repository.isInPlaylist(ep.id)
                _isDownloaded.value = ep.downloadPath != null
            }
        }
    }

    fun play() {
        val ep = _episode.value ?: return
        val artworkUrl = _podcast.value?.artworkUrl ?: ""
        playerManager.play(ep, artworkUrl)
    }

    fun togglePlaylist() {
        viewModelScope.launch {
            val ep = _episode.value ?: return@launch
            if (_isInPlaylist.value) {
                repository.removeFromPlaylist(ep.id)
                _isInPlaylist.value = false
            } else {
                repository.addToPlaylist(ep.id)
                _isInPlaylist.value = true
            }
        }
    }

    fun download() {
        viewModelScope.launch {
            val ep = _episode.value ?: return@launch
            if (ep.downloadPath == null) {
                repository.downloadEpisode(ep)
                _isDownloaded.value = true
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    onBack: () -> Unit,
    viewModel: EpisodeDetailViewModel = hiltViewModel(),
) {
    val episode by viewModel.episode.collectAsState()
    val podcast by viewModel.podcast.collectAsState()
    val isInPlaylist by viewModel.isInPlaylist.collectAsState()
    val isDownloaded by viewModel.isDownloaded.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    episode?.title ?: "Episode",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Podcast info
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = podcast?.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = podcast?.title ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = episode?.title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (episode?.pubDate?.isNotEmpty() == true) {
                        Text(
                            text = episode?.pubDate ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if ((episode?.duration ?: 0) > 0) {
                        Text(
                            text = formatDuration(episode?.duration ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::play,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play")
                }

                OutlinedButton(
                    onClick = viewModel::togglePlaylist,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        if (isInPlaylist) Icons.Default.PlaylistRemove else Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isInPlaylist) "Remove" else "Queue")
                }

                OutlinedButton(
                    onClick = viewModel::download,
                    enabled = !isDownloaded,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isDownloaded) "Saved" else "Download")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Description
            Text(
                text = "Description",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val desc = episode?.description ?: ""
            val cleanDesc = Html.fromHtml(desc, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            Text(
                text = cleanDesc.ifEmpty { "No description available." },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
