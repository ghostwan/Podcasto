package com.ghostwan.podcasto

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.player.PlayerManager
import com.ghostwan.podcasto.player.LanguageSelectionRequest
import com.ghostwan.podcasto.ui.screens.*

@Composable
fun PodcastoNavHost(
    navHostViewModel: NavHostViewModel = hiltViewModel(),
    playerManager: PlayerManager = navHostViewModel.playerManager,
    repository: com.ghostwan.podcasto.data.repository.PodcastRepository = navHostViewModel.repository,
    driveBackupManager: com.ghostwan.podcasto.data.backup.GoogleDriveBackupManager = navHostViewModel.driveBackupManager,
    openPlayerRequest: MutableState<Boolean> = mutableStateOf(false),
    sharedYouTubeUrl: MutableState<String?> = mutableStateOf(null),
) {
    val navController = rememberNavController()
    val playerState by playerManager.playerState.collectAsState()
    val showHidden by navHostViewModel.showHidden.collectAsState()

    // Initialize player
    LaunchedEffect(Unit) {
        playerManager.initialize()
    }

    var showPlayer by remember { mutableStateOf(false) }
    var pendingTagId by remember { mutableStateOf<Long?>(null) }

    // Open player when requested from notification tap
    LaunchedEffect(openPlayerRequest.value) {
        if (openPlayerRequest.value) {
            showPlayer = true
            openPlayerRequest.value = false
        }
    }

    // Navigate to discover screen when a YouTube URL is shared (only in full flavor)
    LaunchedEffect(sharedYouTubeUrl.value) {
        val url = sharedYouTubeUrl.value
        if (BuildConfig.YOUTUBE_ENABLED && url != null) {
            navController.navigate("discover?sharedUrl=${java.net.URLEncoder.encode(url, "UTF-8")}") {
                launchSingleTop = true
            }
            sharedYouTubeUrl.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    MiniPlayer(
                        playerState = playerState,
                        playerManager = playerManager,
                        onExpand = { showPlayer = true },
                    )
                    NavigationBar {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route

                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Subscriptions, contentDescription = stringResource(R.string.nav_subscriptions)) },
                            label = { Text(stringResource(R.string.nav_library)) },
                            selected = currentRoute == "subscriptions",
                            onClick = {
                                navController.navigate("subscriptions") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.nav_playlist)) },
                            label = { Text(stringResource(R.string.nav_playlist)) },
                            selected = currentRoute == "playlist",
                            onClick = {
                                navController.navigate("playlist") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.FiberNew, contentDescription = stringResource(R.string.nav_new_episodes)) },
                            label = { Text(stringResource(R.string.nav_new_episodes)) },
                            selected = currentRoute == "new_episodes",
                            onClick = {
                                navController.navigate("new_episodes") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "subscriptions",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(
                    route = "discover?sharedUrl={sharedUrl}",
                    arguments = listOf(
                        navArgument("sharedUrl") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { backStackEntry ->
                    val sharedUrl = backStackEntry.arguments?.getString("sharedUrl") ?: ""
                    DiscoverScreen(
                        onPodcastClick = { podcast ->
                            val feedUrl = java.net.URLEncoder.encode(podcast.feedUrl ?: "", "UTF-8")
                            val artworkUrl = java.net.URLEncoder.encode(podcast.artworkUrl600 ?: podcast.artworkUrl100 ?: "", "UTF-8")
                            val name = java.net.URLEncoder.encode(podcast.collectionName, "UTF-8")
                            val artist = java.net.URLEncoder.encode(podcast.artistName, "UTF-8")
                            navController.navigate("podcast/${podcast.collectionId}?feedUrl=$feedUrl&artworkUrl=$artworkUrl&collectionName=$name&artistName=$artist") {
                                launchSingleTop = true
                            }
                        },
                        onYouTubeChannelClick = { preview ->
                            val feedUrl = java.net.URLEncoder.encode(preview.feedUrl, "UTF-8")
                            val artworkUrl = java.net.URLEncoder.encode(preview.artworkUrl, "UTF-8")
                            val name = java.net.URLEncoder.encode(preview.title, "UTF-8")
                            val artist = java.net.URLEncoder.encode(preview.author, "UTF-8")
                            navController.navigate("podcast/${preview.id}?feedUrl=$feedUrl&artworkUrl=$artworkUrl&collectionName=$name&artistName=$artist") {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() },
                        sharedUrl = sharedUrl,
                    )
                }

                composable("subscriptions") {
                    SubscriptionsScreen(
                        onPodcastClick = { podcastId ->
                            navController.navigate("podcast/$podcastId") {
                                launchSingleTop = true
                            }
                        },
                        onDiscoverClick = {
                            navController.navigate("discover") {
                                launchSingleTop = true
                            }
                        },
                        onSettingsClick = {
                            navController.navigate("settings") {
                                launchSingleTop = true
                            }
                        },
                        pendingTagId = pendingTagId,
                        onPendingTagConsumed = { pendingTagId = null },
                        showHidden = showHidden,
                        onToggleShowHidden = { navHostViewModel.toggleShowHidden() },
                    )
                }

                composable("playlist") {
                    PlaylistScreen(
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId") {
                                launchSingleTop = true
                            }
                        },
                        showHidden = showHidden,
                    )
                }

                composable("new_episodes") {
                    NewEpisodesScreen(
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId") {
                                launchSingleTop = true
                            }
                        },
                        onHistoryClick = {
                            navController.navigate("history") {
                                launchSingleTop = true
                            }
                        },
                        showHidden = showHidden,
                    )
                }

                composable("history") {
                    HistoryScreen(
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId") {
                                launchSingleTop = true
                            }
                        },
                        onBack = { navController.popBackStack() },
                        showHidden = showHidden,
                    )
                }

                composable(
                    route = "podcast/{podcastId}?feedUrl={feedUrl}&artworkUrl={artworkUrl}&collectionName={collectionName}&artistName={artistName}",
                    arguments = listOf(
                        navArgument("podcastId") { type = NavType.LongType },
                        navArgument("feedUrl") { type = NavType.StringType; defaultValue = "" },
                        navArgument("artworkUrl") { type = NavType.StringType; defaultValue = "" },
                        navArgument("collectionName") { type = NavType.StringType; defaultValue = "" },
                        navArgument("artistName") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    PodcastDetailScreen(
                        onBack = { navController.popBackStack() },
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId") {
                                launchSingleTop = true
                            }
                        },
                        onTagClick = { tagId ->
                            pendingTagId = tagId
                            navController.navigate("subscriptions") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable(
                    route = "episode/{episodeId}",
                    arguments = listOf(
                        navArgument("episodeId") { type = NavType.LongType },
                    ),
                ) {
                    EpisodeDetailScreen(
                        onBack = { navController.popBackStack() },
                        onPodcastClick = { podcastId ->
                            navController.navigate("podcast/$podcastId") {
                                launchSingleTop = true
                            }
                        },
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        repository = repository,
                        driveBackupManager = driveBackupManager,
                    )
                }
            }
        }

        // PlayerScreen overlay — always keeps Scaffold mounted underneath
        if (showPlayer) {
            PlayerScreen(
                playerManager = playerManager,
                onBack = { showPlayer = false },
                onGoToPlaylist = {
                    showPlayer = false
                    navController.navigate("playlist") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onGoToPodcast = { podcastId ->
                    showPlayer = false
                    navController.navigate("podcast/$podcastId") {
                        launchSingleTop = true
                    }
                },
                repository = repository,
            )
        }

        // Language selection dialog for YouTube episodes with multiple audio languages
        val languageRequest by playerManager.languageSelectionRequest.collectAsState()
        languageRequest?.let { request ->
            LanguageSelectionDialog(
                request = request,
                onLanguageSelected = { languageCode ->
                    playerManager.playWithLanguage(request.episode, request.artworkUrl, languageCode)
                },
                onDismiss = {
                    playerManager.dismissLanguageSelection()
                },
            )
        }

        // Restart dialog for already-played episodes
        val restartRequest by playerManager.restartRequest.collectAsState()
        restartRequest?.let {
            RestartEpisodeDialog(
                onRestart = { playerManager.restartFromBeginning() },
                onSkip = { playerManager.skipPlayed() },
                onDismiss = { playerManager.dismissRestartRequest() },
            )
        }
    }
}

@Composable
private fun LanguageSelectionDialog(
    request: LanguageSelectionRequest,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_audio_language)) },
        text = {
            Column {
                Text(
                    text = request.episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                request.availableLanguages.entries.sortedBy { it.value }.forEach { (code, displayName) ->
                    TextButton(
                        onClick = { onLanguageSelected(code) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
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

@Composable
private fun RestartEpisodeDialog(
    onRestart: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.episode_already_played_title)) },
        text = { Text(stringResource(R.string.episode_already_played_message)) },
        confirmButton = {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.restart_from_beginning))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.skip_episode))
            }
        },
    )
}
