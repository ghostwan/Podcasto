package com.ghostwan.podcasto.data.remote

import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedAudioStream(val url: String, val durationSeconds: Long)

data class ResolvedVideoStream(
    val videoUrl: String,
    val audioUrl: String,
    val durationSeconds: Long,
    val width: Int,
    val height: Int,
)

data class StreamSizeInfo(
    val audioSize: Long,
    val videoSize: Long,
    val videoResolution: String,
)

data class AudioLanguageOptions(
    val videoUrl: String,
    val durationSeconds: Long,
    val availableLanguages: Map<String, String>,
    val defaultAudioUrl: String,
    val originalLanguageCode: String? = null,
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

/**
 * Stub YouTubeExtractor for the 'store' flavor.
 * YouTube features are disabled — all methods throw UnsupportedOperationException.
 */
@Singleton
class YouTubeExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        fun isYouTubeUrl(url: String): Boolean = false
        fun isYouTubeChannelUrl(url: String): Boolean = false
        fun isYouTubeVideoUrl(url: String): Boolean = false
    }

    suspend fun getChannelInfo(channelUrl: String): YouTubeChannelInfo {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun fetchChannelVideos(channelId: String): List<YouTubeVideo> {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun resolveAudioStreamUrl(videoUrl: String): ResolvedAudioStream {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun getAvailableLanguages(videoUrl: String): AudioLanguageOptions {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun resolveAudioStreamForLanguage(videoUrl: String, languageCode: String): ResolvedAudioStream {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun resolveVideoStreamUrl(videoUrl: String, languageCode: String? = null): ResolvedVideoStream {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }

    suspend fun getStreamSizes(videoUrl: String): StreamSizeInfo {
        throw UnsupportedOperationException("YouTube is not available in this build")
    }
}
