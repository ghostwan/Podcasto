package com.music.podcasto.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.music.podcasto.data.local.*
import com.music.podcasto.data.remote.ITunesApiService
import com.music.podcasto.data.remote.ITunesPodcast
import com.music.podcasto.data.remote.RssParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    private val iTunesApi: ITunesApiService,
    private val rssParser: RssParser,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val playlistDao: PlaylistDao,
    private val tagDao: TagDao,
    @ApplicationContext private val context: Context,
) {

    // --- Search ---
    suspend fun searchPodcasts(query: String): List<ITunesPodcast> {
        return iTunesApi.searchPodcasts(query).results
    }

    // --- Podcast ---
    fun getSubscribedPodcasts(): Flow<List<PodcastEntity>> = podcastDao.getSubscribedPodcasts()

    suspend fun getPodcastById(id: Long): PodcastEntity? = podcastDao.getPodcastById(id)

    suspend fun subscribeToPodcast(podcast: ITunesPodcast) {
        val feedUrl = podcast.feedUrl ?: return
        val entity = PodcastEntity(
            id = podcast.collectionId,
            title = podcast.collectionName,
            author = podcast.artistName,
            description = "",
            feedUrl = feedUrl,
            artworkUrl = podcast.artworkUrl600 ?: podcast.artworkUrl100 ?: "",
            subscribed = true,
        )
        podcastDao.insertPodcast(entity)
        refreshPodcastEpisodes(entity)
    }

    suspend fun subscribeToPodcastFromDetail(podcastEntity: PodcastEntity, episodes: List<EpisodeEntity> = emptyList()) {
        val subscribed = podcastEntity.copy(subscribed = true)
        podcastDao.insertPodcast(subscribed)
        if (episodes.isNotEmpty()) {
            episodeDao.insertEpisodes(episodes)
        }
        // Also refresh from RSS to get full data
        refreshPodcastEpisodes(subscribed)
    }

    suspend fun unsubscribe(podcastId: Long) {
        podcastDao.updateSubscription(podcastId, false)
    }

    suspend fun refreshPodcastEpisodes(podcast: PodcastEntity) = withContext(Dispatchers.IO) {
        try {
            val feed = rssParser.parseFeed(podcast.feedUrl)
            val updatedPodcast = podcast.copy(
                description = feed.description.ifEmpty { podcast.description },
                artworkUrl = feed.imageUrl.ifEmpty { podcast.artworkUrl },
                author = feed.author.ifEmpty { podcast.author },
            )
            podcastDao.insertPodcast(updatedPodcast)
            val episodes = feed.episodes.mapIndexed { index, rssEpisode ->
                EpisodeEntity(
                    id = (podcast.id * 100000 + index),
                    podcastId = podcast.id,
                    title = rssEpisode.title,
                    description = rssEpisode.description,
                    audioUrl = rssEpisode.audioUrl,
                    pubDate = rssEpisode.pubDate,
                    duration = parseDuration(rssEpisode.duration),
                )
            }
            episodeDao.insertEpisodes(episodes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchPodcastPreview(feedUrl: String, collectionId: Long, artworkUrl: String, collectionName: String, artistName: String): Pair<PodcastEntity, List<EpisodeEntity>> = withContext(Dispatchers.IO) {
        val feed = rssParser.parseFeed(feedUrl)
        val podcast = PodcastEntity(
            id = collectionId,
            title = feed.title.ifEmpty { collectionName },
            author = feed.author.ifEmpty { artistName },
            description = feed.description,
            feedUrl = feedUrl,
            artworkUrl = feed.imageUrl.ifEmpty { artworkUrl },
            subscribed = false,
        )
        val episodes = feed.episodes.mapIndexed { index, rssEpisode ->
            EpisodeEntity(
                id = (collectionId * 100000 + index),
                podcastId = collectionId,
                title = rssEpisode.title,
                description = rssEpisode.description,
                audioUrl = rssEpisode.audioUrl,
                pubDate = rssEpisode.pubDate,
                duration = parseDuration(rssEpisode.duration),
            )
        }
        Pair(podcast, episodes)
    }

    // --- Episodes ---
    fun getEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>> =
        episodeDao.getEpisodesForPodcast(podcastId)

    suspend fun getEpisodeById(id: Long): EpisodeEntity? = episodeDao.getEpisodeById(id)

    // --- Playlist ---
    fun getPlaylistEpisodes(): Flow<List<EpisodeEntity>> = playlistDao.getPlaylistEpisodes()

    fun getPlaylistItems(): Flow<List<PlaylistItemEntity>> = playlistDao.getPlaylistItems()

    suspend fun addToPlaylist(episodeId: Long) {
        if (!playlistDao.isInPlaylist(episodeId)) {
            val position = playlistDao.getNextPosition()
            playlistDao.insertPlaylistItem(PlaylistItemEntity(episodeId = episodeId, position = position))
        }
    }

    suspend fun removeFromPlaylist(episodeId: Long) = playlistDao.removeFromPlaylist(episodeId)

    suspend fun isInPlaylist(episodeId: Long): Boolean = playlistDao.isInPlaylist(episodeId)

    suspend fun clearPlaylist() = playlistDao.clearPlaylist()

    // --- Tags ---
    fun getAllTags(): Flow<List<TagEntity>> = tagDao.getAllTags()

    fun getTagsForPodcast(podcastId: Long): Flow<List<TagEntity>> = tagDao.getTagsForPodcast(podcastId)

    fun getPodcastsForTag(tagId: Long): Flow<List<PodcastEntity>> = tagDao.getPodcastsForTag(tagId)

    suspend fun createTag(name: String): Long = tagDao.insertTag(TagEntity(name = name))

    suspend fun deleteTag(tag: TagEntity) = tagDao.deleteTag(tag)

    suspend fun addTagToPodcast(podcastId: Long, tagId: Long) =
        tagDao.insertPodcastTagCrossRef(PodcastTagCrossRef(podcastId, tagId))

    suspend fun removeTagFromPodcast(podcastId: Long, tagId: Long) =
        tagDao.deletePodcastTagCrossRef(PodcastTagCrossRef(podcastId, tagId))

    // --- Download ---
    suspend fun downloadEpisode(episode: EpisodeEntity): Long = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "episodes")
        downloadDir.mkdirs()
        val fileName = "episode_${episode.id}.mp3"
        val destFile = File(downloadDir, fileName)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
            .setTitle(episode.title)
            .setDescription("Downloading episode...")
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadId = dm.enqueue(request)
        episodeDao.updateDownloadPath(episode.id, destFile.absolutePath)
        downloadId
    }

    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>> = episodeDao.getDownloadedEpisodes()

    private fun parseDuration(raw: String): Long {
        if (raw.isEmpty()) return 0
        return try {
            val parts = raw.split(":")
            when (parts.size) {
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                1 -> parts[0].toLongOrNull() ?: 0
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }
}
