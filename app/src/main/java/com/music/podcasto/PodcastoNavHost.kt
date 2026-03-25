package com.music.podcasto

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.music.podcasto.R
import com.music.podcasto.player.PlayerManager
import com.music.podcasto.ui.screens.*

@Composable
fun PodcastoNavHost(
    playerManager: PlayerManager = hiltViewModel<NavHostViewModel>().playerManager,
    repository: com.music.podcasto.data.repository.PodcastRepository = hiltViewModel<NavHostViewModel>().repository,
    openPlayerRequest: MutableState<Boolean> = mutableStateOf(false),
) {
    val navController = rememberNavController()
    val playerState by playerManager.playerState.collectAsState()

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
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
                composable("discover") {
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
                        onBack = { navController.popBackStack() },
                    )
                }

                composable("subscriptions") {
                    val tagId = pendingTagId
                    LaunchedEffect(tagId) {
                        if (tagId != null) {
                            pendingTagId = null
                        }
                    }
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
                        initialTagId = tagId,
                    )
                }

                composable("playlist") {
                    PlaylistScreen(
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId") {
                                launchSingleTop = true
                            }
                        },
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
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
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
                repository = repository,
            )
        }
    }
}
