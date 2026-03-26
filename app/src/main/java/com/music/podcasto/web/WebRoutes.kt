package com.music.podcasto.web

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.music.podcasto.BuildConfig
import com.music.podcasto.data.local.PodcastEntity
import com.music.podcasto.data.local.PodcastTagCrossRef
import com.music.podcasto.data.local.TagEntity
import com.music.podcasto.data.repository.PodcastRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL
import java.net.URLEncoder

@Serializable
data class PodcastResponse(
    val id: Long,
    val title: String,
    val author: String,
    val description: String,
    val feedUrl: String,
    val artworkUrl: String,
    val subscribed: Boolean,
    val tags: List<TagResponse> = emptyList(),
)

@Serializable
data class TagResponse(
    val id: Long,
    val name: String,
)

@Serializable
data class SearchResult(
    val collectionId: Long,
    val collectionName: String,
    val artistName: String,
    val artworkUrl100: String?,
    val artworkUrl600: String?,
    val feedUrl: String?,
    val alreadySubscribed: Boolean = false,
)

@Serializable
data class SubscribeRequest(
    val collectionId: Long,
    val collectionName: String,
    val artistName: String,
    val artworkUrl: String,
    val feedUrl: String,
)

@Serializable
data class AiSuggestion(
    val name: String,
    val reason: String,
    val searchQuery: String,
)

@Serializable
data class AiResponse(
    val suggestions: List<AiSuggestion>,
    val intro: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

fun configureRoutes(context: Context, repository: PodcastRepository) : Routing.() -> Unit = {

    // Serve the web UI
    get("/") {
        val html = context.assets.open("web/index.html").bufferedReader().readText()
        call.respondText(html, ContentType.Text.Html)
    }
    get("/style.css") {
        val css = context.assets.open("web/style.css").bufferedReader().readText()
        call.respondText(css, ContentType.Text.CSS)
    }
    get("/app.js") {
        val js = context.assets.open("web/app.js").bufferedReader().readText()
        call.respondText(js, ContentType.Application.JavaScript)
    }

    // API routes
    route("/api") {

        // GET /api/podcasts — list subscribed podcasts (with tags)
        get("/podcasts") {
            val podcasts = repository.getSubscribedPodcasts().first()
            val response = podcasts.map { p ->
                val podcastTags = repository.getTagsForPodcast(p.id).first()
                PodcastResponse(
                    id = p.id,
                    title = p.title,
                    author = p.author,
                    description = p.description,
                    feedUrl = p.feedUrl,
                    artworkUrl = p.artworkUrl,
                    subscribed = p.subscribed,
                    tags = podcastTags.map { TagResponse(it.id, it.name) },
                )
            }
            call.respond(response)
        }

        // GET /api/podcasts/:id/tags — get tags for a podcast
        get("/podcasts/{id}/tags") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val tags = repository.getTagsForPodcast(podcastId).first()
            call.respond(tags.map { TagResponse(it.id, it.name) })
        }

        // DELETE /api/podcasts/:id — unsubscribe
        delete("/podcasts/{id}") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            repository.unsubscribe(podcastId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "unsubscribed"))
        }

        // POST /api/subscribe — subscribe to a podcast
        post("/subscribe") {
            try {
                val request = call.receive<SubscribeRequest>()
                val feedUrl = request.feedUrl.ifEmpty { null }
                val podcast = com.music.podcasto.data.remote.ITunesPodcast(
                    collectionId = request.collectionId,
                    collectionName = request.collectionName,
                    artistName = request.artistName,
                    artworkUrl100 = request.artworkUrl,
                    artworkUrl600 = request.artworkUrl,
                    feedUrl = feedUrl,
                    collectionViewUrl = null,
                )
                repository.subscribeToPodcast(podcast)
                call.respond(HttpStatusCode.OK, mapOf("status" to "subscribed"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Subscribe failed"))
            }
        }

        // GET /api/search?q=...&country=... — search iTunes
        get("/search") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing query parameter 'q'"))
            val country = call.request.queryParameters["country"]
            try {
                val subscribedIds = repository.getSubscribedPodcasts().first().map { it.id }.toSet()
                val results = repository.searchPodcasts(query, country)
                val response = results.map { r ->
                    SearchResult(
                        collectionId = r.collectionId,
                        collectionName = r.collectionName,
                        artistName = r.artistName,
                        artworkUrl100 = r.artworkUrl100,
                        artworkUrl600 = r.artworkUrl600,
                        feedUrl = r.feedUrl,
                        alreadySubscribed = r.collectionId in subscribedIds,
                    )
                }
                call.respond(response)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Search failed"))
            }
        }

        // GET /api/tags — list all tags
        get("/tags") {
            val tags = repository.getAllTags().first()
            call.respond(tags.map { TagResponse(it.id, it.name) })
        }

        // POST /api/tags — create a tag
        post("/tags") {
            val body = call.receive<Map<String, String>>()
            val name = body["name"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'name'"))
            val id = repository.createTag(name)
            call.respond(HttpStatusCode.Created, TagResponse(id, name))
        }

        // DELETE /api/tags/:id — delete a tag
        delete("/tags/{id}") {
            val tagId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tag ID"))
            val tags = repository.getAllTags().first()
            val tag = tags.find { it.id == tagId }
                ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Tag not found"))
            repository.deleteTag(tag)
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // POST /api/podcasts/:id/tags/:tagId — assign tag
        post("/podcasts/{id}/tags/{tagId}") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val tagId = call.parameters["tagId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tag ID"))
            repository.addTagToPodcast(podcastId, tagId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "assigned"))
        }

        // DELETE /api/podcasts/:id/tags/:tagId — remove tag
        delete("/podcasts/{id}/tags/{tagId}") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val tagId = call.parameters["tagId"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid tag ID"))
            repository.removeTagFromPodcast(podcastId, tagId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
        }

        // GET /api/discover — AI-powered podcast discovery
        get("/discover") {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Gemini API key not configured. Set GEMINI_API_KEY in local.properties"),
                )
            }

            try {
                val podcasts = repository.getSubscribedPodcasts().first()
                if (podcasts.isEmpty()) {
                    return@get call.respond(
                        HttpStatusCode.OK,
                        AiResponse(emptyList(), "Subscribe to some podcasts first so I can make recommendations!"),
                    )
                }

                val libraryDesc = podcasts.joinToString("\n") { "- ${it.title} by ${it.author}" }

                val prompt = """You are a podcast recommendation expert. Based on the user's podcast library below, suggest 6 new podcasts they might enjoy. The suggestions should be diverse but related to their interests.

User's library:
$libraryDesc

Respond ONLY with a valid JSON object in this exact format, no markdown, no code blocks:
{"intro": "A short personalized intro sentence about their taste", "suggestions": [{"name": "Podcast Name", "reason": "Short reason why they'd like it", "searchQuery": "search query to find it on iTunes"}]}"""

                val model = GenerativeModel(
                    modelName = "gemini-2.0-flash",
                    apiKey = apiKey,
                )

                val response = model.generateContent(prompt)
                val text = response.text?.trim() ?: ""

                // Parse the JSON response
                val cleanJson = text
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val parsed = Json.decodeFromString<AiResponse>(cleanJson)
                call.respond(parsed)
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("AI discovery failed: ${e.message}"),
                )
            }
        }
    }
}
