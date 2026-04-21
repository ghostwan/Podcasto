package com.ghostwan.podcasto.ui.screens

import com.ghostwan.podcasto.BuildConfig
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.repository.PodcastRepository
import com.ghostwan.podcasto.player.PlayerManager
import com.ghostwan.podcasto.player.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(
    playerState: PlayerState,
    playerManager: PlayerManager,
    onExpand: () -> Unit,
) {
    if (playerState.currentEpisode == null) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AsyncImage(
                    model = playerState.podcastArtworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
                if (BuildConfig.YOUTUBE_ENABLED && playerState.podcastSourceType == "youtube") {
                    YouTubeBadge(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playerState.currentEpisode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playerState.duration > 0) {
                    LinearProgressIndicator(
                        progress = { (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(3.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { playerManager.togglePlayPause() }) {
                Icon(
                    if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerManager: PlayerManager,
    onBack: () -> Unit,
    onGoToPlaylist: () -> Unit = {},
    onGoToPodcast: (Long) -> Unit = {},
    repository: PodcastRepository? = null,
) {
    val playerState by playerManager.playerState.collectAsState()
    val episode = playerState.currentEpisode
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity

    var showBookmarkDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenLandscape by remember { mutableStateOf(true) }

    // Single shared PlayerView — reused between inline and fullscreen to avoid
    // surface re-attachment issues that cause the video to disappear.
    val sharedPlayerView = remember(context) {
        PlayerView(context).apply {
            useController = false
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    // Keep the player reference in sync
    LaunchedEffect(playerManager.getController()) {
        sharedPlayerView.player = playerManager.getController()
    }

    // Keep screen on while video is playing
    val keepScreenOn = playerState.isVideoMode && playerState.isPlaying
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Intercept system back gesture — exit fullscreen first, then close player
    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onBack()
        }
    }

    // Manage orientation and system bars for fullscreen
    DisposableEffect(isFullscreen, fullscreenLandscape) {
        if (isFullscreen && activity != null) {
            activity.requestedOrientation = if (fullscreenLandscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity.window?.let { window ->
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        onDispose {
            if (activity != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity.window?.let { window ->
                    window.insetsController?.show(WindowInsets.Type.systemBars())
                }
            }
        }
    }

    // Exit fullscreen only if user explicitly disabled video mode (not during episode transition)
    LaunchedEffect(playerState.isVideoMode) {
        if (!playerState.isVideoMode && !playerManager.preferVideoMode) {
            isFullscreen = false
        }
    }

    if (showBookmarkDialog && repository != null && episode != null) {
        AddBookmarkDialog(
            onConfirm = { comment ->
                scope.launch {
                    val positionMs = playerManager.getCurrentPositionMs()
                    repository.addBookmark(episode.id, positionMs, comment)
                }
                showBookmarkDialog = false
            },
            onDismiss = { showBookmarkDialog = false },
        )
    }

    // Fullscreen video mode
    if (isFullscreen && playerState.isVideoMode && episode != null) {
        FullscreenVideoPlayer(
            playerManager = playerManager,
            playerState = playerState,
            sharedPlayerView = sharedPlayerView,
            onExitFullscreen = { isFullscreen = false },
            isLandscape = fullscreenLandscape,
            onToggleOrientation = { fullscreenLandscape = !fullscreenLandscape },
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.now_playing_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                // Bookmark button
                if (repository != null && episode != null) {
                    IconButton(onClick = { showBookmarkDialog = true }) {
                        Icon(Icons.Default.Bookmark, contentDescription = stringResource(R.string.add_bookmark))
                    }
                }
                // Go to playlist button
                IconButton(onClick = onGoToPlaylist) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.go_to_playlist))
                }
            },
        )

        if (episode == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Artwork or Video
                if (playerState.isVideoMode) {
                    // Video player view with fullscreen button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        AndroidView(
                            factory = {
                                (sharedPlayerView.parent as? ViewGroup)?.removeView(sharedPlayerView)
                                sharedPlayerView
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Fullscreen buttons overlay
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                        ) {
                            // Portrait fullscreen
                            IconButton(
                                onClick = {
                                    fullscreenLandscape = false
                                    isFullscreen = true
                                },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.5f),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Default.StayCurrentPortrait,
                                    contentDescription = stringResource(R.string.fullscreen),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            // Landscape fullscreen
                            IconButton(
                                onClick = {
                                    fullscreenLandscape = true
                                    isFullscreen = true
                                },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = Color.Black.copy(alpha = 0.5f),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Default.StayCurrentLandscape,
                                    contentDescription = stringResource(R.string.fullscreen),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                } else {
                    // Artwork — tap to go to podcast
                    Box {
                        AsyncImage(
                            model = playerState.podcastArtworkUrl,
                            contentDescription = stringResource(R.string.go_to_podcast),
                            modifier = Modifier
                                .size(280.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onGoToPodcast(episode.podcastId) },
                            contentScale = ContentScale.Crop,
                        )
                        if (BuildConfig.YOUTUBE_ENABLED && playerState.podcastSourceType == "youtube") {
                            YouTubeBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Title
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Progress
                val progress = if (playerState.duration > 0) {
                    (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        if (playerState.duration > 0) {
                            playerManager.seekTo((newValue * playerState.duration).toLong())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(playerState.currentPosition / 1000),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = formatDuration(playerState.duration / 1000),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { playerManager.playPrevious() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.previous_episode),
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    IconButton(
                        onClick = { playerManager.skipBackward() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = stringResource(R.string.rewind_10s),
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    FilledIconButton(
                        onClick = { playerManager.togglePlayPause() },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    IconButton(
                        onClick = { playerManager.skipForward() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.Forward30,
                            contentDescription = stringResource(R.string.forward_30s),
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    IconButton(
                        onClick = { playerManager.playNext() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.next_episode),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Volume normalization toggle + Video toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = playerState.volumeNormEnabled,
                        onClick = { playerManager.toggleVolumeNormalization() },
                        label = { Text(stringResource(R.string.volume_normalization)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    // Video/Audio toggle — only for YouTube episodes
                    if (BuildConfig.YOUTUBE_ENABLED && playerState.podcastSourceType == "youtube") {
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = playerState.isVideoMode,
                            onClick = { playerManager.toggleVideoMode() },
                            label = {
                                Text(
                                    if (playerState.isVideoMode) stringResource(R.string.switch_to_audio)
                                    else stringResource(R.string.switch_to_video)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (playerState.isVideoMode) Icons.Default.MusicNote else Icons.Default.Videocam,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = false,
                            onClick = { playerManager.launchYouTubeApp() },
                            label = {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.launch_youtube_app_title),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Fullscreen video player — fills the entire screen in landscape with immersive mode.
 * Controls are shown as an overlay that auto-hides after a few seconds.
 */
@Composable
fun FullscreenVideoPlayer(
    playerManager: PlayerManager,
    playerState: PlayerState,
    sharedPlayerView: PlayerView,
    onExitFullscreen: () -> Unit,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
) {
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                showControls = !showControls
            },
    ) {
        // Video player fills entire screen
        AndroidView(
            factory = {
                (sharedPlayerView.parent as? ViewGroup)?.removeView(sharedPlayerView)
                sharedPlayerView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Controls overlay
        if (showControls) {
            // Semi-transparent background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
            )

            // Top bar: title + exit fullscreen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExitFullscreen) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = stringResource(R.string.exit_fullscreen),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onToggleOrientation) {
                    Icon(
                        Icons.Default.ScreenRotation,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Text(
                    text = playerState.currentEpisode?.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            // Center: play/pause + skip controls
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        playerManager.skipBackward()
                        showControls = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.rewind_10s),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = {
                        playerManager.togglePlayPause()
                        showControls = true
                    },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }

                IconButton(
                    onClick = {
                        playerManager.skipForward()
                        showControls = true
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Forward30,
                        contentDescription = stringResource(R.string.forward_30s),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            // Bottom: progress bar + time
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                val progress = if (playerState.duration > 0) {
                    (playerState.currentPosition.toFloat() / playerState.duration).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = progress,
                    onValueChange = { newValue ->
                        if (playerState.duration > 0) {
                            playerManager.seekTo((newValue * playerState.duration).toLong())
                        }
                        showControls = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(playerState.currentPosition / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                    Text(
                        text = formatDuration(playerState.duration / 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
