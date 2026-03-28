package com.ghostwan.podcasto.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

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
        private val HANDLE_REGEX = Regex("""youtube\.com/@([a-zA-Z0-9._-]+)""")
        private val CUSTOM_URL_REGEX = Regex("""youtube\.com/c/([a-zA-Z0-9._-]+)""")

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
                    request.dataToSend()?.let { okhttp3.RequestBody.create(null, it) }
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
     * Resolve the audio stream URL for a YouTube video.
     * Uses NewPipe Extractor to get the best audio-only stream.
     * These URLs expire, so they must be resolved at play time.
     */
    suspend fun resolveAudioStreamUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        ensureInitialized()
        Log.d(TAG, "Resolving audio stream for: $videoUrl")
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

        // Prefer audio-only streams, sorted by bitrate (highest first)
        val audioStreams = info.audioStreams
            .sortedByDescending { it.averageBitrate }

        val bestStream = audioStreams.firstOrNull()
            ?: throw Exception("No audio streams found for $videoUrl")

        Log.d(TAG, "Resolved audio stream: ${bestStream.content} (${bestStream.averageBitrate}kbps, ${bestStream.format?.name})")
        bestStream.content
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
