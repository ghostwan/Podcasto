package com.music.podcasto

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.music.podcasto.player.PlayerManager
import com.music.podcasto.ui.screens.*
import javax.inject.Inject

@Composable
fun PodcastoNavHost(
    playerManager: PlayerManager = hiltViewModel<NavHostViewModel>().playerManager,
) {
    val navController = rememberNavController()
    val playerState by playerManager.playerState.collectAsState()

    // Initialize player
    LaunchedEffect(Unit) {
        playerManager.initialize()
    }

    var showPlayer by remember { mutableStateOf(false) }

    if (showPlayer) {
        PlayerScreen(
            playerManager = playerManager,
            onBack = { showPlayer = false },
        )
    } else {
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
                            icon = { Icon(Icons.Default.Explore, contentDescription = "Discover") },
                            label = { Text("Discover") },
                            selected = currentRoute == "discover",
                            onClick = {
                                navController.navigate("discover") {
                                    popUpTo("discover") { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Subscriptions, contentDescription = "Subscriptions") },
                            label = { Text("Library") },
                            selected = currentRoute == "subscriptions",
                            onClick = {
                                navController.navigate("subscriptions") {
                                    popUpTo("discover")
                                    launchSingleTop = true
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Playlist") },
                            label = { Text("Playlist") },
                            selected = currentRoute == "playlist",
                            onClick = {
                                navController.navigate("playlist") {
                                    popUpTo("discover")
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
                startDestination = "discover",
                modifier = Modifier.padding(innerPadding),
            ) {
                composable("discover") {
                    DiscoverScreen(
                        onPodcastClick = { podcast ->
                            val feedUrl = java.net.URLEncoder.encode(podcast.feedUrl ?: "", "UTF-8")
                            val artworkUrl = java.net.URLEncoder.encode(podcast.artworkUrl600 ?: podcast.artworkUrl100 ?: "", "UTF-8")
                            val name = java.net.URLEncoder.encode(podcast.collectionName, "UTF-8")
                            val artist = java.net.URLEncoder.encode(podcast.artistName, "UTF-8")
                            navController.navigate("podcast/${podcast.collectionId}?feedUrl=$feedUrl&artworkUrl=$artworkUrl&collectionName=$name&artistName=$artist")
                        },
                    )
                }

                composable("subscriptions") {
                    SubscriptionsScreen(
                        onPodcastClick = { podcastId ->
                            navController.navigate("podcast/$podcastId")
                        },
                    )
                }

                composable("playlist") {
                    PlaylistScreen(
                        onEpisodeClick = { episodeId ->
                            navController.navigate("episode/$episodeId")
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
                            navController.navigate("episode/$episodeId")
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
    }
}
