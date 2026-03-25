package com.music.podcasto.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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
    val duration: String,
)

@Singleton
class RssParser @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    suspend fun parseFeed(feedUrl: String): RssFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).build()
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
        var insideImage = false
        var currentTag = ""
        var epTitle = ""
        var epDescription = ""
        var epAudioUrl = ""
        var epPubDate = ""
        var epDuration = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when {
                        currentTag == "item" -> {
                            insideItem = true
                            epTitle = ""
                            epDescription = ""
                            epAudioUrl = ""
                            epPubDate = ""
                            epDuration = ""
                        }
                        currentTag == "image" && !insideItem -> {
                            insideImage = true
                        }
                        currentTag == "enclosure" && insideItem -> {
                            epAudioUrl = parser.getAttributeValue(null, "url") ?: ""
                        }
                        currentTag == "itunes:image" || (parser.name == "image" && parser.namespace?.contains("itunes") == true) -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null && !insideItem) {
                                feedImageUrl = href
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (insideItem) {
                            when (currentTag) {
                                "title" -> epTitle = text
                                "description", "summary" -> if (epDescription.isEmpty()) epDescription = text
                                "pubDate" -> epPubDate = text
                                "itunes:duration", "duration" -> epDuration = text
                            }
                        } else if (insideImage) {
                            when (currentTag) {
                                "url" -> feedImageUrl = text
                            }
                        } else {
                            when (currentTag) {
                                "title" -> if (feedTitle.isEmpty()) feedTitle = text
                                "description", "summary" -> if (feedDescription.isEmpty()) feedDescription = text
                                "itunes:author", "author" -> if (feedAuthor.isEmpty()) feedAuthor = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            if (epAudioUrl.isNotEmpty()) {
                                episodes.add(
                                    RssEpisode(
                                        title = epTitle,
                                        description = epDescription,
                                        audioUrl = epAudioUrl,
                                        pubDate = epPubDate,
                                        duration = epDuration,
                                    )
                                )
                            }
                            insideItem = false
                        }
                        "image" -> insideImage = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        return RssFeed(
            title = feedTitle,
            description = feedDescription,
            author = feedAuthor,
            imageUrl = feedImageUrl,
            episodes = episodes,
        )
    }
}
