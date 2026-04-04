package com.ghostwan.podcasto.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedAudioStream(val url: String, val durationSeconds: Long)

/**
 * Resolved video stream with both video and audio URLs.
 * Video streams on YouTube are often separate (DASH), so we need both.
 */
data class ResolvedVideoStream(
    val videoUrl: String,
    val audioUrl: String,
    val durationSeconds: Long,
    val width: Int,
    val height: Int,
)

/**
 * Stream sizes in bytes for download choice dialog.
 */
data class StreamSizeInfo(
    val audioSize: Long,
    val videoSize: Long,
    val videoResolution: String,
)

/**
 * Represents available audio languages for a YouTube video.
 * If only one language (or none specified), availableLanguages will have 0-1 entry.
 */
data class AudioLanguageOptions(
    val videoUrl: String,
    val durationSeconds: Long,
    /** language code -> display name, e.g. "fr" -> "French", "en" -> "English" */
    val availableLanguages: Map<String, String>,
    /** The default (highest bitrate) audio stream URL (for single-language videos) */
    val defaultAudioUrl: String,
)

data class YouTubeChannelInfo(
    val channelId: String,
    val name: String,
    val description: String,
    val avatarUrl: String,
    val bannerUrl: String,
)

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val pubDateTimestamp: Long,
    val thumbnailUrl: String,
    val videoUrl: String,
)

@Singleton
class YouTubeExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val YT_RSS_BASE = "https://www.youtube.com/feeds/videos.xml?channel_id="
        private val CHANNEL_ID_REGEX = Regex("""youtube\.com/channel/([a-zA-Z0-9_-]+)""")

        @Volatile
        private var initialized = false

        fun isYouTubeUrl(url: String): Boolean {
            val lower = url.lowercase().trim()
            return lower.contains("youtube.com/channel/") ||
                    lower.contains("youtube.com/@") ||
                    lower.contains("youtube.com/c/") ||
                    lower.contains("youtube.com/watch?") ||
                    lower.contains("youtu.be/")
        }

        fun isYouTubeChannelUrl(url: String): Boolean {
            val lower = url.lowercase().trim()
            return lower.contains("youtube.com/channel/") ||
                    lower.contains("youtube.com/@") ||
                    lower.contains("youtube.com/c/")
        }

        fun isYouTubeVideoUrl(url: String): Boolean {
            val lower = url.lowercase().trim()
            return lower.contains("youtube.com/watch?") || lower.contains("youtu.be/")
        }
    }

    /**
     * Custom Downloader implementation for NewPipe Extractor using OkHttp.
     */
    private inner class OkHttpDownloader : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val requestBuilder = Request.Builder()
                .url(request.url())
                .method(
                    request.httpMethod(),
                    request.dataToSend()?.let { it.toRequestBody(null) }
                )

            // Add headers from the NewPipe request
            for ((key, values) in request.headers()) {
                for (value in values) {
                    requestBuilder.addHeader(key, value)
                }
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            // Convert OkHttp headers to Map<String, List<String>>
            val responseHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                responseHeaders[name] = response.headers.values(name)
            }

            return Response(
                response.code,
                response.message,
                responseHeaders,
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(OkHttpDownloader())
                    initialized = true
                    Log.d(TAG, "NewPipe Extractor initialized")
                }
            }
        }
    }

    /**
     * Extract channel info from a YouTube channel URL.
     * Supports: youtube.com/channel/ID, youtube.com/@handle, youtube.com/c/name
     */
    suspend fun getChannelInfo(channelUrl: String): YouTubeChannelInfo = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
            val channelId = extractChannelId(info.url) ?: info.id
            YouTubeChannelInfo(
                channelId = channelId,
                name = info.name,
                description = info.description ?: "",
                avatarUrl = info.avatars.firstOrNull()?.url ?: "",
                bannerUrl = info.banners.firstOrNull()?.url ?: "",
            )
        } catch (e: ExtractionException) {
            Log.e(TAG, "Failed to extract channel info from $channelUrl", e)
            throw e
        }
    }

    /**
     * Fetch episodes from a YouTube channel's RSS feed.
     * Returns videos sorted by publication date (newest first).
     */
    suspend fun fetchChannelVideos(channelId: String): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        val feedUrl = "$YT_RSS_BASE$channelId"
        Log.d(TAG, "Fetching YouTube RSS feed: $feedUrl")
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Podcasto/1.0")
            .build()
        val response = okHttpClient.newCall(request).execute()
        val xml = response.body?.string() ?: throw Exception("Empty YouTube RSS response")
        parseYouTubeAtomFeed(xml)
    }

    /**
     * Force re-initialization of NewPipe Extractor.
     * Useful when YouTube changes its API and cached state becomes stale.
     */
    private fun reinitialize() {
        synchronized(this) {
            initialized = false
            NewPipe.init(OkHttpDownloader())
            initialized = true
            Log.d(TAG, "NewPipe Extractor re-initialized")
        }
    }

    /**
     * Internal: get StreamInfo with retry on failure (re-initializes NewPipe Extractor).
     */
    private suspend fun getStreamInfo(videoUrl: String): StreamInfo = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        } catch (e: Exception) {
            Log.w(TAG, "First attempt failed, re-initializing and retrying: ${e.message}")
            reinitialize()
            StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        }
    }

    /**
     * Resolve the audio stream URL for a YouTube video.
     * Uses NewPipe Extractor to get the best audio-only stream.
     * These URLs expire, so they must be resolved at play time.
     * Retries once with a full re-initialization if the first attempt fails.
     * Returns the audio URL and video duration in seconds.
     */
    suspend fun resolveAudioStreamUrl(videoUrl: String): ResolvedAudioStream = withContext(Dispatchers.IO) {
        Log.d(TAG, "Resolving audio stream for: $videoUrl")
        val info = getStreamInfo(videoUrl)

        // Prefer audio-only streams, sorted by bitrate (highest first)
        val audioStreams = info.audioStreams
            .sortedByDescending { it.averageBitrate }

        val bestStream = audioStreams.firstOrNull()
            ?: throw Exception("No audio streams found for $videoUrl")

        Log.d(TAG, "Resolved audio stream: ${bestStream.content} (${bestStream.averageBitrate}kbps, ${bestStream.format?.name}, duration=${info.duration}s)")
        ResolvedAudioStream(url = bestStream.content, durationSeconds = info.duration)
    }

    /**
     * Get available audio languages for a YouTube video.
     * Returns AudioLanguageOptions with the list of distinct locales from audio streams.
     * If the video has only one language (or no locale info), availableLanguages will be empty/single.
     */
    suspend fun getAvailableLanguages(videoUrl: String): AudioLanguageOptions = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking available languages for: $videoUrl")
        val info = getStreamInfo(videoUrl)

        val audioStreams = info.audioStreams
        if (audioStreams.isEmpty()) {
            throw Exception("No audio streams found for $videoUrl")
        }

        // Group streams by locale
        val languageMap = mutableMapOf<String, String>()
        for (stream in audioStreams) {
            val locale = stream.audioLocale
            if (locale != null) {
                val code = locale.language
                if (code.isNotEmpty() && code !in languageMap) {
                    languageMap[code] = locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase() }
                }
            }
        }

        // Default: best stream by bitrate
        val bestStream = audioStreams.sortedByDescending { it.averageBitrate }.first()

        Log.d(TAG, "Available languages: $languageMap (${audioStreams.size} total streams)")
        AudioLanguageOptions(
            videoUrl = videoUrl,
            durationSeconds = info.duration,
            availableLanguages = languageMap,
            defaultAudioUrl = bestStream.content,
        )
    }

    /**
     * Resolve the audio stream URL for a specific language.
     * Falls back to best available stream if the requested language is not found.
     */
    suspend fun resolveAudioStreamForLanguage(videoUrl: String, languageCode: String): ResolvedAudioStream = withContext(Dispatchers.IO) {
        Log.d(TAG, "Resolving audio stream for language '$languageCode': $videoUrl")
        val info = getStreamInfo(videoUrl)

        val audioStreams = info.audioStreams
        if (audioStreams.isEmpty()) {
            throw Exception("No audio streams found for $videoUrl")
        }

        // Try to find streams matching the requested language, pick highest bitrate
        val matchingStreams = audioStreams
            .filter { it.audioLocale?.language == languageCode }
            .sortedByDescending { it.averageBitrate }

        val bestStream = matchingStreams.firstOrNull()
            ?: audioStreams.sortedByDescending { it.averageBitrate }.first()

        Log.d(TAG, "Resolved audio stream for '$languageCode': ${bestStream.content} (${bestStream.averageBitrate}kbps, locale=${bestStream.audioLocale}, duration=${info.duration}s)")
        ResolvedAudioStream(url = bestStream.content, durationSeconds = info.duration)
    }

    /**
     * Resolve the video stream URL for a YouTube video.
     * Returns the best video stream (up to 720p for bandwidth) + best audio stream.
     * YouTube DASH streams are separate: video-only + audio-only must be merged by the player.
     */
    suspend fun resolveVideoStreamUrl(videoUrl: String, languageCode: String? = null): ResolvedVideoStream = withContext(Dispatchers.IO) {
        Log.d(TAG, "Resolving video stream for: $videoUrl (language=$languageCode)")
        val info = getStreamInfo(videoUrl)

        // Get video-only streams, prefer up to 720p, sorted by resolution descending
        val videoStreams = info.videoOnlyStreams
            .filter { it.resolution != null }
            .sortedByDescending { it.getResolutionInt() }

        // Pick best stream at or below 720p, or the lowest available if all are > 720p
        val bestVideo = videoStreams.firstOrNull { it.getResolutionInt() <= 720 }
            ?: videoStreams.lastOrNull()
            ?: throw Exception("No video streams found for $videoUrl")

        // Also need audio — filter by language if specified
        val audioStreams = info.audioStreams
        val bestAudio = if (languageCode != null) {
            // Try matching language first, then fall back to highest bitrate
            audioStreams
                .filter { it.audioLocale?.language == languageCode }
                .sortedByDescending { it.averageBitrate }
                .firstOrNull()
                ?: audioStreams.sortedByDescending { it.averageBitrate }.firstOrNull()
        } else {
            audioStreams.sortedByDescending { it.averageBitrate }.firstOrNull()
        } ?: throw Exception("No audio streams found for $videoUrl")

        Log.d(TAG, "Resolved video: ${bestVideo.resolution} + audio: ${bestAudio.averageBitrate}kbps (locale=${bestAudio.audioLocale}), duration=${info.duration}s")
        ResolvedVideoStream(
            videoUrl = bestVideo.content,
            audioUrl = bestAudio.content,
            durationSeconds = info.duration,
            width = bestVideo.width,
            height = bestVideo.height,
        )
    }

    /**
     * Get estimated download sizes for audio and video streams.
     * Uses HTTP HEAD requests to get Content-Length headers.
     */
    suspend fun getStreamSizes(videoUrl: String): StreamSizeInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "Getting stream sizes for: $videoUrl")
        val info = getStreamInfo(videoUrl)

        val bestAudio = info.audioStreams
            .sortedByDescending { it.averageBitrate }
            .firstOrNull()
            ?: throw Exception("No audio streams found for $videoUrl")

        val videoStreams = info.videoOnlyStreams
            .filter { it.resolution != null }
            .sortedByDescending { it.getResolutionInt() }
        val bestVideo = videoStreams.firstOrNull { it.getResolutionInt() <= 720 }
            ?: videoStreams.lastOrNull()
            ?: throw Exception("No video streams found for $videoUrl")

        val audioSize = getContentLength(bestAudio.content)
        val videoSize = getContentLength(bestVideo.content)

        Log.d(TAG, "Stream sizes — audio: ${audioSize / 1024}KB, video: ${videoSize / 1024}KB (${bestVideo.resolution})")
        StreamSizeInfo(
            audioSize = audioSize,
            videoSize = videoSize,
            videoResolution = bestVideo.resolution ?: "?",
        )
    }

    /**
     * Get Content-Length of a URL via HTTP HEAD request.
     * Falls back to 0 if the request fails or Content-Length is not present.
     */
    private fun getContentLength(url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = okHttpClient.newCall(request).execute()
            response.use {
                it.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get content length for $url: ${e.message}")
            0L
        }
    }

    /**
     * Helper to extract resolution as int from stream resolution string like "720p".
     */
    private fun org.schabi.newpipe.extractor.stream.VideoStream.getResolutionInt(): Int {
        return resolution?.replace("p", "")?.toIntOrNull() ?: 0
    }

    /**
     * Extract channel ID from URL patterns.
     */
    private fun extractChannelId(url: String): String? {
        CHANNEL_ID_REGEX.find(url)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * Parse YouTube Atom feed XML into a list of YouTubeVideo.
     */
    private fun parseYouTubeAtomFeed(xml: String): List<YouTubeVideo> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val videos = mutableListOf<YouTubeVideo>()
        var insideEntry = false
        val textBuilder = StringBuilder()

        var videoId = ""
        var title = ""
        var description = ""
        var pubDate = ""
        var pubDateTimestamp = 0L
        var thumbnailUrl = ""

        val NS_MEDIA = "http://search.yahoo.com/mrss/"
        val NS_YT = "http://www.youtube.com/xml/schemas/2015"

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val ns = parser.namespace ?: ""
                    val localName = parser.name ?: ""
                    textBuilder.clear()

                    when {
                        localName == "entry" -> {
                            insideEntry = true
                            videoId = ""
                            title = ""
                            description = ""
                            pubDate = ""
                            pubDateTimestamp = 0L
                            thumbnailUrl = ""
                        }
                        localName == "videoId" && ns == NS_YT && insideEntry -> {
                            // text will be captured
                        }
                        localName == "thumbnail" && ns == NS_MEDIA && insideEntry -> {
                            thumbnailUrl = parser.getAttributeValue(null, "url") ?: ""
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    parser.text?.let { textBuilder.append(it) }
                }

                XmlPullParser.END_TAG -> {
                    val ns = parser.namespace ?: ""
                    val localName = parser.name ?: ""
                    val text = textBuilder.toString().trim()

                    when {
                        localName == "entry" -> {
                            if (videoId.isNotEmpty()) {
                                videos.add(
                                    YouTubeVideo(
                                        videoId = videoId,
                                        title = title,
                                        description = description,
                                        pubDate = pubDate,
                                        pubDateTimestamp = pubDateTimestamp,
                                        thumbnailUrl = thumbnailUrl.ifEmpty {
                                            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                        },
                                        videoUrl = "https://www.youtube.com/watch?v=$videoId",
                                    )
                                )
                            }
                            insideEntry = false
                        }
                        insideEntry -> {
                            when {
                                localName == "videoId" && ns == NS_YT -> videoId = text
                                localName == "title" && text.isNotEmpty() && title.isEmpty() -> title = text
                                localName == "description" && ns == NS_MEDIA -> description = text
                                localName == "published" -> {
                                    pubDate = text
                                    pubDateTimestamp = RssParser.parsePubDate(text)
                                }
                            }
                        }
                    }
                    textBuilder.clear()
                }
            }
            eventType = parser.next()
        }

        Log.d(TAG, "Parsed ${videos.size} videos from YouTube RSS feed")
        return videos
    }
}
