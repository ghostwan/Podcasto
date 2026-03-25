package com.music.podcasto.ui.screens

import android.graphics.Typeface
import android.text.Html
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.music.podcasto.R
import com.music.podcasto.data.local.BookmarkEntity
import com.music.podcasto.data.local.EpisodeEntity
import com.music.podcasto.data.local.PodcastEntity
import com.music.podcasto.data.repository.PodcastRepository
import com.music.podcasto.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.getBookmarksForEpisode(episodeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun togglePlayed() {
        viewModelScope.launch {
            val ep = _episode.value ?: return@launch
            if (ep.played) {
                repository.markAsUnplayed(ep.id)
                _episode.value = ep.copy(played = false, playbackPosition = 0)
            } else {
                repository.markAsPlayed(ep.id)
                _episode.value = ep.copy(played = true, playbackPosition = 0)
            }
        }
    }

    fun addBookmark(comment: String) {
        viewModelScope.launch {
            val positionMs = playerManager.getCurrentPositionMs()
            repository.addBookmark(episodeId, positionMs, comment)
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
        }
    }

    fun seekToBookmark(bookmark: BookmarkEntity) {
        val ep = _episode.value ?: return
        val artworkUrl = _podcast.value?.artworkUrl ?: ""
        // If the episode isn't currently playing, start playing it first
        val currentEpisode = playerManager.playerState.value.currentEpisode
        if (currentEpisode?.id != ep.id) {
            playerManager.play(ep, artworkUrl)
        }
        playerManager.seekTo(bookmark.positionMs)
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
    val bookmarks by viewModel.bookmarks.collectAsState()

    var showBookmarkDialog by remember { mutableStateOf(false) }

    if (showBookmarkDialog) {
        AddBookmarkDialog(
            onConfirm = { comment ->
                viewModel.addBookmark(comment)
                showBookmarkDialog = false
            },
            onDismiss = { showBookmarkDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    episode?.title ?: stringResource(R.string.episode_fallback),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                    val formattedDate = formatPubDate(episode?.pubDateTimestamp ?: 0, episode?.pubDate ?: "")
                    if (formattedDate.isNotEmpty()) {
                        Text(
                            text = formattedDate,
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

            // Action buttons: icon on top, label below
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionIconButton(
                    icon = Icons.Default.PlayArrow,
                    label = stringResource(R.string.play),
                    onClick = viewModel::play,
                )
                ActionIconButton(
                    icon = if (isInPlaylist) Icons.Default.PlaylistRemove else Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = if (isInPlaylist) stringResource(R.string.remove) else stringResource(R.string.queue),
                    onClick = viewModel::togglePlaylist,
                )
                ActionIconButton(
                    icon = Icons.Default.Download,
                    label = if (isDownloaded) stringResource(R.string.saved) else stringResource(R.string.download),
                    onClick = viewModel::download,
                    enabled = !isDownloaded,
                )
                ActionIconButton(
                    icon = if (episode?.played == true) Icons.Default.RadioButtonUnchecked else Icons.Default.Check,
                    label = if (episode?.played == true) stringResource(R.string.unplayed) else stringResource(R.string.played),
                    onClick = viewModel::togglePlayed,
                )
                ActionIconButton(
                    icon = Icons.Default.Bookmark,
                    label = stringResource(R.string.bookmark),
                    onClick = { showBookmarkDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Bookmarks section
            if (bookmarks.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.bookmarks_count, bookmarks.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                bookmarks.forEach { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        onSeek = { viewModel.seekToBookmark(bookmark) },
                        onDelete = { viewModel.deleteBookmark(bookmark) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Description
            Text(
                text = stringResource(R.string.description_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val desc = episode?.description ?: ""
            if (desc.isNotEmpty()) {
                val linkColor = MaterialTheme.colorScheme.primary
                val annotatedDesc = remember(desc) { htmlToAnnotatedString(desc, linkColor) }
                val uriHandler = LocalUriHandler.current
                ClickableText(
                    text = annotatedDesc,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    onClick = { offset ->
                        annotatedDesc.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { annotation ->
                                try { uriHandler.openUri(annotation.item) } catch (_: Exception) {}
                            }
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.no_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
fun ActionIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun BookmarkItem(
    bookmark: BookmarkEntity,
    onSeek: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onSeek),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDuration(bookmark.positionMs / 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (bookmark.comment.isNotEmpty()) {
                    Text(
                        text = bookmark.comment,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_bookmark),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun AddBookmarkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_bookmark)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.add_bookmark_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.comment_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(comment.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

fun htmlToAnnotatedString(html: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    return buildAnnotatedString {
        append(spanned.toString())
        // Apply style spans
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    Typeface.BOLD_ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                        start, end,
                    )
                }
                is UnderlineSpan -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline), start, end,
                )
                is URLSpan -> {
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                        start, end,
                    )
                    addStringAnnotation("URL", span.url, start, end)
                }
            }
        }
    }.let { annotated ->
        // Trim trailing whitespace/newlines
        val text = annotated.text
        val trimEnd = text.length - text.trimEnd().length
        if (trimEnd > 0) {
            annotated.subSequence(0, text.length - trimEnd) as AnnotatedString
        } else {
            annotated
        }
    }
}
