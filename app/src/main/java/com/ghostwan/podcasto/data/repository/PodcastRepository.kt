package com.ghostwan.podcasto.data.repository

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.ghostwan.podcasto.data.local.*
import com.ghostwan.podcasto.data.remote.ApplePodcastsScraper
import com.ghostwan.podcasto.data.remote.ITunesApiService
import com.ghostwan.podcasto.data.remote.ITunesPodcast
import com.ghostwan.podcasto.data.remote.RssParser
import com.ghostwan.podcasto.data.remote.ResolvedAudioStream
import com.ghostwan.podcasto.data.remote.ResolvedVideoStream
import com.ghostwan.podcasto.data.remote.AudioLanguageOptions
import com.ghostwan.podcasto.data.remote.StreamSizeInfo
import com.ghostwan.podcasto.data.remote.YouTubeExtractor
import com.ghostwan.podcasto.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import com.ghostwan.podcasto.R
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastRepository @Inject constructor(
    private val iTunesApi: ITunesApiService,
    private val rssParser: RssParser,
    private val applePodcastsScraper: ApplePodcastsScraper,
    private val youTubeExtractor: YouTubeExtractor,
    private val okHttpClient: OkHttpClient,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val playlistDao: PlaylistDao,
    private val tagDao: TagDao,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    @ApplicationContext private val context: Context,
) {

    // --- Search ---
    suspend fun searchPodcasts(query: String, country: String? = null): List<ITunesPodcast> {
        return iTunesApi.searchPodcasts(query, country = country).results
    }

    // --- Podcast ---
    fun getSubscribedPodcasts(): Flow<List<PodcastEntity>> = podcastDao.getSubscribedPodcasts()

    suspend fun getAllSubscribedPodcasts(): List<PodcastEntity> = podcastDao.getAllSubscribedPodcasts()

    fun getHiddenPodcastIds(): Flow<Set<Long>> = podcastDao.getHiddenPodcastIds()
        .map { it.toSet() }

    suspend fun getPodcastById(id: Long): PodcastEntity? = podcastDao.getPodcastById(id)

    suspend fun subscribeToPodcast(podcast: ITunesPodcast) {
        val feedUrl = podcast.feedUrl
            ?: applePodcastsScraper.fetchFeedUrl(podcast.collectionId)
            ?: return
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
        refreshPodcastEpisodes(subscribed)
    }

    suspend fun unsubscribe(podcastId: Long) {
        podcastDao.updateSubscription(podcastId, false)
    }

    suspend fun setHidden(podcastId: Long, hidden: Boolean) {
        podcastDao.updateHidden(podcastId, hidden)
    }

    suspend fun refreshPodcastEpisodes(podcast: PodcastEntity) = withContext(Dispatchers.IO) {
        if (podcast.sourceType == "youtube") {
            if (BuildConfig.YOUTUBE_ENABLED) {
                refreshYouTubeEpisodes(podcast)
            }
            return@withContext
        }
        try {
            val feed = rssParser.parseFeed(podcast.feedUrl)
            val updatedPodcast = podcast.copy(
                description = feed.description.ifEmpty { podcast.description },
                artworkUrl = feed.imageUrl.ifEmpty { podcast.artworkUrl },
                author = feed.author.ifEmpty { podcast.author },
            )
            podcastDao.insertPodcast(updatedPodcast)
            // Load existing episodes once, indexed by audioUrl for O(1) lookups
            val existingByAudioUrl = episodeDao.getEpisodesForPodcastList(podcast.id)
                .associateBy { it.audioUrl }
            val episodes = feed.episodes.mapIndexed { index, rssEpisode ->
                // Match existing episode by audioUrl (stable) instead of by index (shifts when new episodes are added)
                val existingEpisode = existingByAudioUrl[rssEpisode.audioUrl]
                EpisodeEntity(
                    id = (podcast.id * 100000 + index),
                    podcastId = podcast.id,
                    title = rssEpisode.title,
                    description = rssEpisode.description,
                    audioUrl = rssEpisode.audioUrl,
                    pubDate = rssEpisode.pubDate,
                    pubDateTimestamp = rssEpisode.pubDateTimestamp,
                    duration = parseDuration(rssEpisode.duration),
                    downloadPath = existingEpisode?.downloadPath,
                    played = existingEpisode?.played ?: false,
                    playbackPosition = existingEpisode?.playbackPosition ?: 0,
                )
            }
            episodeDao.insertEpisodes(episodes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchPodcastPreview(feedUrl: String, collectionId: Long, artworkUrl: String, collectionName: String, artistName: String): Pair<PodcastEntity, List<EpisodeEntity>> = withContext(Dispatchers.IO) {
        val resolvedFeedUrl = feedUrl.ifEmpty {
            applePodcastsScraper.fetchFeedUrl(collectionId)
                ?: throw IllegalStateException("No feed URL available for podcast $collectionId")
        }
        val feed = rssParser.parseFeed(resolvedFeedUrl)
        val podcast = PodcastEntity(
            id = collectionId,
            title = feed.title.ifEmpty { collectionName },
            author = feed.author.ifEmpty { artistName },
            description = feed.description,
            feedUrl = resolvedFeedUrl,
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
                pubDateTimestamp = rssEpisode.pubDateTimestamp,
                duration = parseDuration(rssEpisode.duration),
            )
        }
        Pair(podcast, episodes)
    }

    // --- YouTube ---

    /**
     * Preview a YouTube channel as a podcast without subscribing.
     * Returns the podcast entity and its episodes without persisting to the database.
     */
    suspend fun fetchYouTubeChannelPreview(channelUrl: String): Pair<PodcastEntity, List<EpisodeEntity>> = withContext(Dispatchers.IO) {
        check(BuildConfig.YOUTUBE_ENABLED) { "YouTube is not available in this build" }
        val channelInfo = youTubeExtractor.getChannelInfo(channelUrl)
        val podcastId = channelInfo.channelId.hashCode().toLong().let { if (it < 0) -it else it }
        val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=${channelInfo.channelId}"

        val entity = PodcastEntity(
            id = podcastId,
            title = channelInfo.name,
            author = channelInfo.name,
            description = channelInfo.description,
            feedUrl = feedUrl,
            artworkUrl = channelInfo.avatarUrl,
            subscribed = false,
            sourceType = "youtube",
        )

        val videos = youTubeExtractor.fetchChannelVideos(channelInfo.channelId)
        val episodes = videos.mapIndexed { index, video ->
            EpisodeEntity(
                id = podcastId * 100000 + index,
                podcastId = podcastId,
                title = video.title,
                description = video.description,
                audioUrl = video.videoUrl,
                pubDate = video.pubDate,
                pubDateTimestamp = video.pubDateTimestamp,
                duration = 0,
            )
        }
        Pair(entity, episodes)
    }

    /**
     * Preview a YouTube channel from its feed URL (used when navigating from discover preview).
     * Extracts the channel ID from the feed URL and fetches videos without persisting.
     */
    suspend fun fetchYouTubePreviewFromFeed(
        feedUrl: String, podcastId: Long, artworkUrl: String, title: String, author: String
    ): Pair<PodcastEntity, List<EpisodeEntity>> = withContext(Dispatchers.IO) {
        check(BuildConfig.YOUTUBE_ENABLED) { "YouTube is not available in this build" }
        val channelId = feedUrl.substringAfter("channel_id=", "").takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Invalid YouTube feed URL: $feedUrl")

        val entity = PodcastEntity(
            id = podcastId,
            title = title,
            author = author,
            description = "",
            feedUrl = feedUrl,
            artworkUrl = artworkUrl,
            subscribed = false,
            sourceType = "youtube",
        )

        val videos = youTubeExtractor.fetchChannelVideos(channelId)
        val episodes = videos.mapIndexed { index, video ->
            EpisodeEntity(
                id = podcastId * 100000 + index,
                podcastId = podcastId,
                title = video.title,
                description = video.description,
                audioUrl = video.videoUrl,
                pubDate = video.pubDate,
                pubDateTimestamp = video.pubDateTimestamp,
                duration = 0,
            )
        }
        Pair(entity, episodes)
    }

    /**
     * Subscribe to a YouTube channel as a podcast.
     * Uses NewPipe Extractor to get channel info, then YouTube RSS for episodes.
     */
    suspend fun subscribeToYouTubeChannel(channelUrl: String): PodcastEntity = withContext(Dispatchers.IO) {
        check(BuildConfig.YOUTUBE_ENABLED) { "YouTube is not available in this build" }
        val channelInfo = youTubeExtractor.getChannelInfo(channelUrl)
        // Use channel ID hash as podcast ID to avoid collision with iTunes IDs
        val podcastId = channelInfo.channelId.hashCode().toLong().let { if (it < 0) -it else it }
        val feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=${channelInfo.channelId}"

        val entity = PodcastEntity(
            id = podcastId,
            title = channelInfo.name,
            author = channelInfo.name,
            description = channelInfo.description,
            feedUrl = feedUrl,
            artworkUrl = channelInfo.avatarUrl,
            subscribed = true,
            sourceType = "youtube",
        )
        podcastDao.insertPodcast(entity)
        refreshYouTubeEpisodes(entity)
        entity
    }

    /**
     * Refresh episodes for a YouTube-type podcast using its RSS feed.
     */
    private suspend fun refreshYouTubeEpisodes(podcast: PodcastEntity) {
        try {
            // Extract channel ID from feed URL
            val channelId = podcast.feedUrl
                .substringAfter("channel_id=", "")
                .takeIf { it.isNotEmpty() }
                ?: return

            val videos = youTubeExtractor.fetchChannelVideos(channelId)
            // Load existing episodes once, indexed by audioUrl for O(1) lookups
            val existingByAudioUrl = episodeDao.getEpisodesForPodcastList(podcast.id)
                .associateBy { it.audioUrl }
            val episodes = videos.mapIndexed { index, video ->
                val episodeId = podcast.id * 100000 + index
                // Match existing episode by audioUrl (stable) instead of by index
                val existingEpisode = existingByAudioUrl[video.videoUrl]
                EpisodeEntity(
                    id = episodeId,
                    podcastId = podcast.id,
                    title = video.title,
                    description = video.description,
                    audioUrl = video.videoUrl, // Store video URL; resolved at play time
                    pubDate = video.pubDate,
                    pubDateTimestamp = video.pubDateTimestamp,
                    duration = 0, // YouTube RSS doesn't provide duration
                    downloadPath = existingEpisode?.downloadPath,
                    videoDownloadPath = existingEpisode?.videoDownloadPath,
                    played = existingEpisode?.played ?: false,
                    playbackPosition = existingEpisode?.playbackPosition ?: 0,
                )
            }
            episodeDao.insertEpisodes(episodes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolve the actual audio stream URL for an episode.
     * For YouTube episodes, this uses NewPipe Extractor (URL expires, must resolve at play time).
     * Returns a ResolvedAudioStream with url and duration (duration is 0 for non-YouTube).
     * For regular RSS episodes, returns the stored audioUrl directly.
     */
    suspend fun resolveAudioUrl(episode: EpisodeEntity): ResolvedAudioStream {
        if (BuildConfig.YOUTUBE_ENABLED) {
            // First check if the URL itself is a YouTube video URL (direct detection)
            if (YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl)) {
                return youTubeExtractor.resolveAudioStreamUrl(episode.audioUrl)
            }
            // Fallback: check podcast sourceType
            val podcast = podcastDao.getPodcastById(episode.podcastId)
            if (podcast?.sourceType == "youtube") {
                return youTubeExtractor.resolveAudioStreamUrl(episode.audioUrl)
            }
        }
        return ResolvedAudioStream(url = episode.audioUrl, durationSeconds = 0)
    }

    /**
     * Get available audio languages for a YouTube episode.
     * Returns null if the episode is not YouTube-based.
     */
    suspend fun getAvailableLanguages(episode: EpisodeEntity): AudioLanguageOptions? {
        if (!BuildConfig.YOUTUBE_ENABLED) return null
        if (YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl)) {
            return youTubeExtractor.getAvailableLanguages(episode.audioUrl)
        }
        val podcast = podcastDao.getPodcastById(episode.podcastId)
        if (podcast?.sourceType == "youtube") {
            return youTubeExtractor.getAvailableLanguages(episode.audioUrl)
        }
        return null
    }

    /**
     * Resolve the audio URL for a specific language.
     * For non-YouTube episodes, ignores the language parameter.
     */
    suspend fun resolveAudioUrlForLanguage(episode: EpisodeEntity, languageCode: String): ResolvedAudioStream {
        if (BuildConfig.YOUTUBE_ENABLED) {
            if (YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl)) {
                return youTubeExtractor.resolveAudioStreamForLanguage(episode.audioUrl, languageCode)
            }
            val podcast = podcastDao.getPodcastById(episode.podcastId)
            if (podcast?.sourceType == "youtube") {
                return youTubeExtractor.resolveAudioStreamForLanguage(episode.audioUrl, languageCode)
            }
        }
        return ResolvedAudioStream(url = episode.audioUrl, durationSeconds = 0)
    }

    /**
     * Resolve the video stream URL for a YouTube episode.
     * Returns separate video-only and audio-only DASH URLs that the player must merge.
     * If languageCode is specified, the audio stream will match that language.
     * Returns null for non-YouTube episodes.
     */
    suspend fun resolveVideoUrl(episode: EpisodeEntity, languageCode: String? = null): ResolvedVideoStream? {
        if (!BuildConfig.YOUTUBE_ENABLED) return null
        if (YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl)) {
            return youTubeExtractor.resolveVideoStreamUrl(episode.audioUrl, languageCode)
        }
        val podcast = podcastDao.getPodcastById(episode.podcastId)
        if (podcast?.sourceType == "youtube") {
            return youTubeExtractor.resolveVideoStreamUrl(episode.audioUrl, languageCode)
        }
        return null
    }

    /**
     * Get estimated download sizes for a YouTube episode's audio and video streams.
     * Returns null for non-YouTube episodes.
     */
    suspend fun getStreamSizes(episode: EpisodeEntity): StreamSizeInfo? {
        if (!BuildConfig.YOUTUBE_ENABLED) return null
        if (YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl)) {
            return youTubeExtractor.getStreamSizes(episode.audioUrl)
        }
        val podcast = podcastDao.getPodcastById(episode.podcastId)
        if (podcast?.sourceType == "youtube") {
            return youTubeExtractor.getStreamSizes(episode.audioUrl)
        }
        return null
    }

    // --- Episodes ---
    fun getEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>> =
        episodeDao.getEpisodesForPodcast(podcastId)

    fun getRecentEpisodesWithArtwork(): Flow<List<EpisodeWithArtwork>> =
        episodeDao.getRecentEpisodesWithArtwork()

    fun getRecentEpisodesWithArtworkForTag(tagId: Long): Flow<List<EpisodeWithArtwork>> =
        episodeDao.getRecentEpisodesWithArtworkForTag(tagId)

    fun getUnplayedEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>> =
        episodeDao.getUnplayedEpisodesForPodcast(podcastId)

    fun getLatestEpisodeTimestampPerPodcast(): Flow<Map<Long, Long>> =
        episodeDao.getLatestEpisodeTimestampPerPodcast()
            .map { list -> list.associate { it.podcastId to it.latestTimestamp } }

    suspend fun getEpisodeById(id: Long): EpisodeEntity? = episodeDao.getEpisodeById(id)

    suspend fun markAsPlayed(episodeId: Long) = episodeDao.markAsPlayed(episodeId)

    suspend fun markAsUnplayed(episodeId: Long) {
        episodeDao.updatePlayed(episodeId, false)
        episodeDao.updatePlaybackPosition(episodeId, 0)
    }

    suspend fun updatePlaybackPosition(episodeId: Long, position: Long) {
        episodeDao.updatePlaybackPosition(episodeId, position)
    }

    suspend fun updateEpisodeDuration(episodeId: Long, duration: Long) {
        episodeDao.updateDuration(episodeId, duration)
    }

    // --- Playlist ---
    fun getPlaylistEpisodes(): Flow<List<EpisodeEntity>> = playlistDao.getPlaylistEpisodes()

    suspend fun getPlaylistEpisodesWithArtworkList(): List<EpisodeWithArtwork> = playlistDao.getPlaylistEpisodesWithArtworkList()

    fun getPlaylistEpisodesWithArtwork(): Flow<List<EpisodeWithArtwork>> = playlistDao.getPlaylistEpisodesWithArtwork()

    fun getPlaylistItems(): Flow<List<PlaylistItemEntity>> = playlistDao.getPlaylistItems()

    suspend fun addToPlaylist(episodeId: Long) {
        if (!playlistDao.isInPlaylist(episodeId)) {
            val position = playlistDao.getNextPosition()
            playlistDao.insertPlaylistItem(PlaylistItemEntity(episodeId = episodeId, position = position))
        }
    }

    suspend fun addToPlaylistTop(episodeId: Long) {
        if (!playlistDao.isInPlaylist(episodeId)) {
            playlistDao.shiftAllPositions()
            playlistDao.insertPlaylistItem(PlaylistItemEntity(episodeId = episodeId, position = 0))
        }
    }

    suspend fun removeFromPlaylist(episodeId: Long) = playlistDao.removeFromPlaylist(episodeId)

    suspend fun isInPlaylist(episodeId: Long): Boolean = playlistDao.isInPlaylist(episodeId)

    suspend fun clearPlaylist() = playlistDao.clearPlaylist()

    suspend fun updatePlaylistPositions(episodeIds: List<Long>) {
        episodeIds.forEachIndexed { index, episodeId ->
            playlistDao.updatePosition(episodeId, index)
        }
    }

    suspend fun autoAddLatestEpisodes() {
        val episodes = episodeDao.getLatestEpisodesFromSubscriptions()
        for (ep in episodes) {
            addToPlaylist(ep.id)
        }
    }

    suspend fun autoAddLatestEpisodesForTag(tagId: Long) {
        val episodes = episodeDao.getLatestEpisodesForTag(tagId)
        for (ep in episodes) {
            addToPlaylist(ep.id)
        }
    }

    // --- Tags ---
    fun getAllTags(): Flow<List<TagEntity>> = tagDao.getAllTags()

    fun getTagsForPodcast(podcastId: Long): Flow<List<TagEntity>> = tagDao.getTagsForPodcast(podcastId)

    fun getPodcastsForTag(tagId: Long): Flow<List<PodcastEntity>> = tagDao.getPodcastsForTag(tagId)

    suspend fun createTag(name: String): Long = tagDao.insertTag(TagEntity(name = name))

    suspend fun deleteTag(tag: TagEntity) = tagDao.deleteTag(tag)

    suspend fun updateTagPositions(orderedTagIds: List<Long>) {
        orderedTagIds.forEachIndexed { index, tagId ->
            tagDao.updateTagPosition(tagId, index)
        }
    }

    suspend fun addTagToPodcast(podcastId: Long, tagId: Long) =
        tagDao.insertPodcastTagCrossRef(PodcastTagCrossRef(podcastId, tagId))

    suspend fun removeTagFromPodcast(podcastId: Long, tagId: Long) =
        tagDao.deletePodcastTagCrossRef(PodcastTagCrossRef(podcastId, tagId))

    // --- Bookmarks ---
    fun getBookmarksForEpisode(episodeId: Long): Flow<List<BookmarkEntity>> =
        bookmarkDao.getBookmarksForEpisode(episodeId)

    suspend fun addBookmark(episodeId: Long, positionMs: Long, comment: String): Long =
        bookmarkDao.insertBookmark(BookmarkEntity(episodeId = episodeId, positionMs = positionMs, comment = comment))

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkDao.deleteBookmark(bookmark)

    suspend fun deleteBookmarkById(id: Long) = bookmarkDao.deleteBookmarkById(id)

    // --- Download ---
    /**
     * Download mode for YouTube episodes.
     */
    enum class DownloadMode { AUDIO, VIDEO, BOTH }

    suspend fun downloadEpisode(episode: EpisodeEntity, downloadMode: DownloadMode = DownloadMode.BOTH): Long = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "episodes")
        downloadDir.mkdirs()

        // Check if this is a YouTube episode that needs URL resolution
        val isYouTube = BuildConfig.YOUTUBE_ENABLED && (
            YouTubeExtractor.isYouTubeVideoUrl(episode.audioUrl) ||
            podcastDao.getPodcastById(episode.podcastId)?.sourceType == "youtube"
        )

        if (isYouTube) {
            // Download audio stream if requested
            if (downloadMode == DownloadMode.AUDIO || downloadMode == DownloadMode.BOTH) {
                val audioStream = youTubeExtractor.resolveAudioStreamUrl(episode.audioUrl)
                val audioFile = File(downloadDir, "episode_${episode.id}_audio.m4a")
                downloadFileWithOkHttp(audioStream.url, audioFile)
                episodeDao.updateDownloadPath(episode.id, audioFile.absolutePath)
            }

            // Download video stream if requested
            if (downloadMode == DownloadMode.VIDEO || downloadMode == DownloadMode.BOTH) {
                try {
                    val videoStream = youTubeExtractor.resolveVideoStreamUrl(episode.audioUrl)
                    val videoFile = File(downloadDir, "episode_${episode.id}_video.mp4")
                    downloadFileWithOkHttp(videoStream.videoUrl, videoFile)
                    episodeDao.updateVideoDownloadPath(episode.id, videoFile.absolutePath)
                    // If video-only mode, also download audio for the video player's audio track
                    if (downloadMode == DownloadMode.VIDEO) {
                        val audioStream = youTubeExtractor.resolveAudioStreamUrl(episode.audioUrl)
                        val audioFile = File(downloadDir, "episode_${episode.id}_audio.m4a")
                        downloadFileWithOkHttp(audioStream.url, audioFile)
                        episodeDao.updateDownloadPath(episode.id, audioFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.w("PodcastRepository", "Failed to download video stream: ${e.message}")
                }
            }

            0L // No DownloadManager ID for OkHttp downloads
        } else {
            // Regular RSS episode — use DownloadManager
            val fileName = "episode_${episode.id}.mp3"
            val destFile = File(downloadDir, fileName)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(episode.audioUrl))
                .setTitle(episode.title)
                .setDescription(context.getString(R.string.downloading_episode))
                .setDestinationUri(Uri.fromFile(destFile))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val downloadId = dm.enqueue(request)
            episodeDao.updateDownloadPath(episode.id, destFile.absolutePath)
            downloadId
        }
    }

    /**
     * Download a file from a URL directly using OkHttp (for YouTube resolved stream URLs).
     */
    private fun downloadFileWithOkHttp(url: String, destFile: File) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: throw Exception("Empty response body")
        }
    }

    suspend fun deleteDownload(episodeId: Long) = withContext(Dispatchers.IO) {
        val episode = episodeDao.getEpisodeById(episodeId) ?: return@withContext
        // Delete audio file
        val audioPath = episode.downloadPath
        if (audioPath != null) {
            val file = File(audioPath)
            if (file.exists()) file.delete()
        }
        episodeDao.updateDownloadPath(episodeId, null)
        // Delete video file
        val videoPath = episode.videoDownloadPath
        if (videoPath != null) {
            val file = File(videoPath)
            if (file.exists()) file.delete()
        }
        episodeDao.updateVideoDownloadPath(episodeId, null)
    }

    // --- History ---
    fun getHistoryWithDetails(): Flow<List<HistoryWithDetails>> = historyDao.getHistoryWithDetails()

    suspend fun addHistoryEntry(episodeId: Long, podcastId: Long) {
        // Remove existing entry for this episode so it appears only once (with the latest timestamp)
        historyDao.deleteHistoryForEpisode(episodeId)
        historyDao.insertHistoryEntry(HistoryEntity(episodeId = episodeId, podcastId = podcastId))
    }

    suspend fun clearHistory() = historyDao.clearHistory()

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

    // --- Backup / Restore ---

    private val backupJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val podcasts = podcastDao.getAllSubscribedPodcasts()
        val podcastIds = podcasts.map { it.id }
        val episodes = episodeDao.getEpisodesForPodcasts(podcastIds)
        val tags = tagDao.getAllTagsList()
        val crossRefs = tagDao.getCrossRefsForPodcasts(podcastIds)
        val bookmarks = bookmarkDao.getAllBookmarks()
        val playlistItems = playlistDao.getAllPlaylistItems()
        val history = historyDao.getAllHistory()

        // Read settings from SharedPreferences
        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val playerPrefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        val settings = BackupSettings(
            geminiApiKey = appPrefs.getString("gemini_api_key", "") ?: "",
            volumeNormalization = playerPrefs.getBoolean("volume_normalization", false),
        )

        val backup = BackupData(
            version = 1,
            podcasts = podcasts.map { it.toBackup() },
            episodes = episodes.map { it.toBackup() },
            tags = tags.map { it.toBackup() },
            podcastTagRefs = crossRefs.map { BackupPodcastTagRef(it.podcastId, it.tagId) },
            bookmarks = bookmarks.map { it.toBackup() },
            playlistItems = playlistItems.map { BackupPlaylistItem(it.episodeId, it.position) },
            history = history.map { it.toBackup() },
            settings = settings,
        )
        backupJson.encodeToString(backup)
    }

    suspend fun importBackup(jsonString: String) = withContext(Dispatchers.IO) {
        val backup = backupJson.decodeFromString<BackupData>(jsonString)

        // Insert podcasts
        for (p in backup.podcasts) {
            podcastDao.insertPodcast(
                PodcastEntity(
                    id = p.id, title = p.title, author = p.author,
                    description = p.description, feedUrl = p.feedUrl,
                    artworkUrl = p.artworkUrl, subscribed = p.subscribed,
                    hidden = p.hidden, sourceType = p.sourceType,
                )
            )
        }

        // Insert episodes
        val episodes = backup.episodes.map { e ->
            EpisodeEntity(
                id = e.id, podcastId = e.podcastId, title = e.title,
                description = e.description, audioUrl = e.audioUrl,
                pubDate = e.pubDate, pubDateTimestamp = e.pubDateTimestamp,
                duration = e.duration, played = e.played,
                playbackPosition = e.playbackPosition,
            )
        }
        episodeDao.insertEpisodes(episodes)

        // Insert tags
        val tags = backup.tags.map { TagEntity(id = it.id, name = it.name) }
        tagDao.insertTags(tags)

        // Insert cross refs
        val crossRefs = backup.podcastTagRefs.map { PodcastTagCrossRef(it.podcastId, it.tagId) }
        tagDao.insertPodcastTagCrossRefs(crossRefs)

        // Insert bookmarks
        val bookmarks = backup.bookmarks.map { b ->
            BookmarkEntity(
                id = b.id, episodeId = b.episodeId, positionMs = b.positionMs,
                comment = b.comment, createdAt = b.createdAt,
            )
        }
        bookmarkDao.insertBookmarks(bookmarks)

        // Insert playlist items
        val playlistItems = backup.playlistItems.map { PlaylistItemEntity(episodeId = it.episodeId, position = it.position) }
        playlistDao.insertPlaylistItems(playlistItems)

        // Insert history
        val historyEntries = backup.history.map { h ->
            HistoryEntity(id = h.id, episodeId = h.episodeId, podcastId = h.podcastId, listenedAt = h.listenedAt)
        }
        historyDao.insertHistoryEntries(historyEntries)

        // Restore settings
        val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val playerPrefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        if (backup.settings.geminiApiKey.isNotEmpty()) {
            appPrefs.edit().putString("gemini_api_key", backup.settings.geminiApiKey).apply()
        }
        playerPrefs.edit().putBoolean("volume_normalization", backup.settings.volumeNormalization).apply()
    }
}

// --- Backup data model ---

@Serializable
data class BackupData(
    val version: Int = 1,
    val podcasts: List<BackupPodcast> = emptyList(),
    val episodes: List<BackupEpisode> = emptyList(),
    val tags: List<BackupTag> = emptyList(),
    val podcastTagRefs: List<BackupPodcastTagRef> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val playlistItems: List<BackupPlaylistItem> = emptyList(),
    val history: List<BackupHistory> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

@Serializable
data class BackupSettings(
    val geminiApiKey: String = "",
    val volumeNormalization: Boolean = false,
)

@Serializable
data class BackupPodcast(
    val id: Long, val title: String, val author: String,
    val description: String, val feedUrl: String, val artworkUrl: String,
    val subscribed: Boolean = true, val hidden: Boolean = false,
    val sourceType: String = "rss",
)

@Serializable
data class BackupEpisode(
    val id: Long, val podcastId: Long, val title: String,
    val description: String, val audioUrl: String, val pubDate: String,
    val pubDateTimestamp: Long = 0, val duration: Long = 0,
    val played: Boolean = false, val playbackPosition: Long = 0,
)

@Serializable
data class BackupTag(val id: Long, val name: String)

@Serializable
data class BackupPodcastTagRef(val podcastId: Long, val tagId: Long)

@Serializable
data class BackupBookmark(
    val id: Long, val episodeId: Long, val positionMs: Long,
    val comment: String, val createdAt: Long,
)

@Serializable
data class BackupPlaylistItem(val episodeId: Long, val position: Int)

@Serializable
data class BackupHistory(
    val id: Long, val episodeId: Long, val podcastId: Long, val listenedAt: Long,
)

// Extension functions for conversion
private fun PodcastEntity.toBackup() = BackupPodcast(
    id = id, title = title, author = author, description = description,
    feedUrl = feedUrl, artworkUrl = artworkUrl, subscribed = subscribed, hidden = hidden,
    sourceType = sourceType,
)

private fun EpisodeEntity.toBackup() = BackupEpisode(
    id = id, podcastId = podcastId, title = title, description = description,
    audioUrl = audioUrl, pubDate = pubDate, pubDateTimestamp = pubDateTimestamp,
    duration = duration, played = played, playbackPosition = playbackPosition,
)

private fun TagEntity.toBackup() = BackupTag(id = id, name = name)

private fun BookmarkEntity.toBackup() = BackupBookmark(
    id = id, episodeId = episodeId, positionMs = positionMs,
    comment = comment, createdAt = createdAt,
)

private fun HistoryEntity.toBackup() = BackupHistory(
    id = id, episodeId = episodeId, podcastId = podcastId, listenedAt = listenedAt,
)
