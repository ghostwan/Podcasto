package com.ghostwan.podcasto.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class RssFeed(
    val title: String,
    val description: String,
    val author: String,
    val imageUrl: String,
    val episodes: List<RssEpisode>,
)

data class RssEpisode(
    val title: String,
    val description: String,
    val audioUrl: String,
    val pubDate: String,
    val pubDateTimestamp: Long,
    val duration: String,
)

@Singleton
class RssParser @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "RssParser"
        private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
        private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
        private const val NS_ATOM = "http://www.w3.org/2005/Atom"

        // Common RSS date formats
        private val DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )

        fun parsePubDate(dateStr: String): Long {
            if (dateStr.isBlank()) return 0L
            val trimmed = dateStr.trim()
            for (fmt in DATE_FORMATS) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US)
                    sdf.isLenient = true
                    val date = sdf.parse(trimmed)
                    if (date != null) return date.time
                } catch (_: Exception) {
                    // try next format
                }
            }
            Log.w(TAG, "Could not parse date: $trimmed")
            return 0L
        }
    }

    suspend fun parseFeed(feedUrl: String): RssFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Podcasto/1.0")
            .build()
        val response = okHttpClient.newCall(request).execute()
        val xml = response.body?.string() ?: throw Exception("Empty response")
        parseXml(xml)
    }

    private fun parseXml(xml: String): RssFeed {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var feedTitle = ""
        var feedDescription = ""
        var feedAuthor = ""
        var feedImageUrl = ""
        val episodes = mutableListOf<RssEpisode>()

        var insideItem = false
        var insideChannel = false
        var insideChannelImage = false

        // Use a stack to track element hierarchy
        val tagStack = mutableListOf<Pair<String, String>>() // (namespace, localName)

        // Accumulators for text content (handles split TEXT events + CDATA)
        val textBuilder = StringBuilder()

        // Current episode fields
        var epTitle = ""
        var epDescription = ""
        var epAudioUrl = ""
        var epPubDate = ""
        var epPubDateTimestamp = 0L
        var epDuration = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val ns = parser.namespace ?: ""
                    val localName = parser.name ?: ""

                    tagStack.add(Pair(ns, localName))
                    textBuilder.clear()

                    when {
                        // RSS <channel>
                        localName == "channel" -> {
                            insideChannel = true
                        }
                        // RSS <item> or Atom <entry>
                        localName == "item" || (localName == "entry" && ns == NS_ATOM) -> {
                            insideItem = true
                            epTitle = ""
                            epDescription = ""
                            epAudioUrl = ""
                            epPubDate = ""
                            epPubDateTimestamp = 0L
                            epDuration = ""
                        }
                        // RSS channel <image> block (not itunes:image)
                        localName == "image" && !insideItem && ns.isEmpty() -> {
                            insideChannelImage = true
                        }
                        // <enclosure> inside item — get audio URL
                        localName == "enclosure" && insideItem -> {
                            val url = parser.getAttributeValue(null, "url") ?: ""
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            // Accept audio/* types, or if no type given accept any URL
                            if (url.isNotEmpty() && (type.startsWith("audio") || type.isEmpty())) {
                                if (epAudioUrl.isEmpty()) {
                                    epAudioUrl = url
                                }
                            }
                        }
                        // Atom <link> with rel="enclosure" inside entry
                        localName == "link" && ns == NS_ATOM && insideItem -> {
                            val rel = parser.getAttributeValue(null, "rel") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            if (rel == "enclosure" && href.isNotEmpty() && (type.startsWith("audio") || type.isEmpty())) {
                                if (epAudioUrl.isEmpty()) {
                                    epAudioUrl = href
                                }
                            }
                        }
                        // <media:content> inside item
                        localName == "content" && ns.contains("media") && insideItem -> {
                            val url = parser.getAttributeValue(null, "url") ?: ""
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            val medium = parser.getAttributeValue(null, "medium") ?: ""
                            if (url.isNotEmpty() && (type.startsWith("audio") || medium == "audio")) {
                                if (epAudioUrl.isEmpty()) {
                                    epAudioUrl = url
                                }
                            }
                        }
                        // itunes:image (href attribute) — feed level
                        localName == "image" && ns == NS_ITUNES && !insideItem -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrEmpty()) {
                                feedImageUrl = href
                            }
                        }
                        // itunes:image inside item (we don't use it currently, but parse for completeness)
                        localName == "image" && ns == NS_ITUNES && insideItem -> {
                            // Episode-level image, could store later
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    // Accumulate text — handles both plain text and CDATA sections
                    val text = parser.text
                    if (text != null) {
                        textBuilder.append(text)
                    }
                }

                XmlPullParser.END_TAG -> {
                    val ns = parser.namespace ?: ""
                    val localName = parser.name ?: ""
                    val text = textBuilder.toString().trim()

                    when {
                        // End of <item>/<entry> — save episode
                        localName == "item" || (localName == "entry" && ns == NS_ATOM) -> {
                            if (epAudioUrl.isNotEmpty()) {
                                episodes.add(
                                    RssEpisode(
                                        title = epTitle,
                                        description = epDescription,
                                        audioUrl = epAudioUrl,
                                        pubDate = epPubDate,
                                        pubDateTimestamp = epPubDateTimestamp,
                                        duration = epDuration,
                                    )
                                )
                            }
                            insideItem = false
                        }
                        // End of channel <image> block
                        localName == "image" && ns.isEmpty() && insideChannelImage -> {
                            insideChannelImage = false
                        }
                        localName == "channel" -> {
                            insideChannel = false
                        }
                        // Process text content
                        insideItem -> {
                            when {
                                // Title
                                localName == "title" && text.isNotEmpty() && epTitle.isEmpty() -> {
                                    epTitle = text
                                }
                                // Description: prefer content:encoded > itunes:summary/description > description
                                localName == "encoded" && ns == NS_CONTENT -> {
                                    // content:encoded is the richest description — always prefer it
                                    epDescription = text
                                }
                                localName == "description" && ns.isEmpty() && text.isNotEmpty() -> {
                                    if (epDescription.isEmpty()) epDescription = text
                                }
                                localName == "summary" && (ns == NS_ITUNES || ns.isEmpty()) && text.isNotEmpty() -> {
                                    if (epDescription.isEmpty()) epDescription = text
                                }
                                // Pub date
                                localName == "pubDate" && text.isNotEmpty() -> {
                                    epPubDate = text
                                    epPubDateTimestamp = parsePubDate(text)
                                }
                                // Atom: <published> or <updated>
                                localName == "published" && ns == NS_ATOM && text.isNotEmpty() -> {
                                    epPubDate = text
                                    epPubDateTimestamp = parsePubDate(text)
                                }
                                localName == "updated" && ns == NS_ATOM && text.isNotEmpty() && epPubDate.isEmpty() -> {
                                    epPubDate = text
                                    epPubDateTimestamp = parsePubDate(text)
                                }
                                // Duration (itunes:duration or plain duration)
                                localName == "duration" && text.isNotEmpty() -> {
                                    epDuration = text
                                }
                            }
                        }
                        insideChannelImage -> {
                            // Inside <image> block: look for <url>
                            if (localName == "url" && text.isNotEmpty() && feedImageUrl.isEmpty()) {
                                feedImageUrl = text
                            }
                        }
                        insideChannel && !insideItem -> {
                            // Channel-level elements
                            when {
                                localName == "title" && text.isNotEmpty() && feedTitle.isEmpty() -> {
                                    feedTitle = text
                                }
                                localName == "description" && ns.isEmpty() && text.isNotEmpty() && feedDescription.isEmpty() -> {
                                    feedDescription = text
                                }
                                localName == "summary" && (ns == NS_ITUNES || ns.isEmpty()) && text.isNotEmpty() && feedDescription.isEmpty() -> {
                                    feedDescription = text
                                }
                                localName == "author" && text.isNotEmpty() && feedAuthor.isEmpty() -> {
                                    feedAuthor = text
                                }
                                localName == "owner" -> {
                                    // itunes:owner contains itunes:name — handled by the author check above
                                }
                                localName == "name" && ns == NS_ITUNES && text.isNotEmpty() && feedAuthor.isEmpty() -> {
                                    feedAuthor = text
                                }
                            }
                        }
                    }

                    // Pop from tag stack
                    if (tagStack.isNotEmpty()) {
                        tagStack.removeAt(tagStack.lastIndex)
                    }
                    textBuilder.clear()
                }
            }
            eventType = parser.next()
        }

        Log.d(TAG, "Parsed feed: title=$feedTitle, episodes=${episodes.size}")
        return RssFeed(
            title = feedTitle,
            description = feedDescription,
            author = feedAuthor,
            imageUrl = feedImageUrl,
            episodes = episodes,
        )
    }
}
