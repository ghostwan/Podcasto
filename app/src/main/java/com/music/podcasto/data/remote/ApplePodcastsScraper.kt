package com.music.podcasto.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes the Apple Podcasts web page to extract the RSS feed URL
 * for podcasts where iTunes API does not provide a feedUrl.
 */
@Singleton
class ApplePodcastsScraper @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ApplePodcastsScraper"
    }

    suspend fun fetchFeedUrl(collectionId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://podcasts.apple.com/podcast/id$collectionId"
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .build()

            val response = okHttpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            // Extract the JSON from <script id="serialized-server-data">...</script>
            val startMarker = """<script type="application/json" id="serialized-server-data">"""
            val startIndex = html.indexOf(startMarker)
            if (startIndex == -1) {
                // Try alternative marker without type attribute
                val altMarker = """id="serialized-server-data">"""
                val altIndex = html.indexOf(altMarker)
                if (altIndex == -1) {
                    Log.w(TAG, "Could not find serialized-server-data script tag")
                    return@withContext null
                }
                val jsonStart = altIndex + altMarker.length
                val jsonEnd = html.indexOf("</script>", jsonStart)
                if (jsonEnd == -1) return@withContext null
                val jsonStr = decodeHtmlEntities(html.substring(jsonStart, jsonEnd))
                return@withContext extractFeedUrl(jsonStr)
            }

            val jsonStart = startIndex + startMarker.length
            val jsonEnd = html.indexOf("</script>", jsonStart)
            if (jsonEnd == -1) {
                Log.w(TAG, "Could not find closing </script> tag")
                return@withContext null
            }

            val jsonStr = decodeHtmlEntities(html.substring(jsonStart, jsonEnd))
            extractFeedUrl(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching feed URL for $collectionId", e)
            null
        }
    }

    private fun extractFeedUrl(jsonStr: String): String? {
        return try {
            val array = JSONArray(jsonStr)
            findFeedUrl(array)
        } catch (e: Exception) {
            try {
                val obj = JSONObject(jsonStr)
                findFeedUrl(obj)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse JSON", e2)
                null
            }
        }
    }

    private fun findFeedUrl(obj: Any?): String? {
        when (obj) {
            is JSONObject -> {
                if (obj.has("feedUrl")) {
                    val feedUrl = obj.optString("feedUrl", "")
                    if (feedUrl.isNotEmpty()) return feedUrl
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val result = findFeedUrl(obj.opt(key))
                    if (result != null) return result
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val result = findFeedUrl(obj.opt(i))
                    if (result != null) return result
                }
            }
        }
        return null
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }
}
