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
import java.io.File

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
    val latestEpisodeTimestamp: Long = 0,
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
data class PreviewRequest(
    val collectionId: Long,
    val collectionName: String,
    val artistName: String,
    val artworkUrl: String,
    val feedUrl: String,
)

@Serializable
data class PreviewResponse(
    val podcast: PodcastResponse,
    val episodes: List<EpisodeResponse>,
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

@Serializable
data class EpisodeResponse(
    val id: Long,
    val podcastId: Long,
    val title: String,
    val description: String,
    val audioUrl: String,
    val pubDate: String,
    val pubDateTimestamp: Long,
    val duration: Long,
    val played: Boolean,
    val playbackPosition: Long,
    val downloadPath: String?,
    val artworkUrl: String = "",
)

@Serializable
data class PlaylistItemResponse(
    val id: Long,
    val podcastId: Long,
    val title: String,
    val description: String,
    val audioUrl: String,
    val pubDate: String,
    val pubDateTimestamp: Long,
    val duration: Long,
    val played: Boolean,
    val playbackPosition: Long,
    val downloadPath: String?,
    val artworkUrl: String,
    val podcastTitle: String,
)

@Serializable
data class BookmarkResponse(
    val id: Long,
    val episodeId: Long,
    val positionMs: Long,
    val comment: String,
    val createdAt: Long,
)

@Serializable
data class PositionUpdate(
    val position: Long,
)

@Serializable
data class BookmarkRequest(
    val positionMs: Long,
    val comment: String = "",
)

@Serializable
data class HistoryResponse(
    val id: Long,
    val episodeId: Long,
    val podcastId: Long,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String,
    val listenedAt: Long,
)

@Serializable
data class ReorderRequest(
    val episodeIds: List<Long>,
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
            val latestTimestamps = repository.getLatestEpisodeTimestampPerPodcast().first()
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
                    latestEpisodeTimestamp = latestTimestamps[p.id] ?: 0L,
                )
            }
            call.respond(response)
        }

        // GET /api/podcasts/:id — get podcast detail
        get("/podcasts/{id}") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val podcast = repository.getPodcastById(podcastId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Podcast not found"))
            val podcastTags = repository.getTagsForPodcast(podcastId).first()
            call.respond(PodcastResponse(
                id = podcast.id,
                title = podcast.title,
                author = podcast.author,
                description = podcast.description,
                feedUrl = podcast.feedUrl,
                artworkUrl = podcast.artworkUrl,
                subscribed = podcast.subscribed,
                tags = podcastTags.map { TagResponse(it.id, it.name) },
            ))
        }

        // GET /api/podcasts/:id/episodes — list episodes for a podcast
        get("/podcasts/{id}/episodes") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val podcast = repository.getPodcastById(podcastId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Podcast not found"))
            val episodes = repository.getEpisodesForPodcast(podcastId).first()
            call.respond(episodes.map { e ->
                EpisodeResponse(
                    id = e.id,
                    podcastId = e.podcastId,
                    title = e.title,
                    description = e.description,
                    audioUrl = e.audioUrl,
                    pubDate = e.pubDate,
                    pubDateTimestamp = e.pubDateTimestamp,
                    duration = e.duration,
                    played = e.played,
                    playbackPosition = e.playbackPosition,
                    downloadPath = e.downloadPath,
                    artworkUrl = podcast.artworkUrl,
                )
            })
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

        // POST /api/preview — fetch podcast detail + episodes from RSS (for non-subscribed podcasts)
        post("/preview") {
            try {
                val request = call.receive<PreviewRequest>()
                // First check if already in DB
                val existing = repository.getPodcastById(request.collectionId)
                if (existing != null && existing.subscribed) {
                    // Already subscribed — return from DB
                    val episodes = repository.getEpisodesForPodcast(existing.id).first()
                    val podcastTags = repository.getTagsForPodcast(existing.id).first()
                    call.respond(PreviewResponse(
                        podcast = PodcastResponse(
                            id = existing.id,
                            title = existing.title,
                            author = existing.author,
                            description = existing.description,
                            feedUrl = existing.feedUrl,
                            artworkUrl = existing.artworkUrl,
                            subscribed = true,
                            tags = podcastTags.map { TagResponse(it.id, it.name) },
                        ),
                        episodes = episodes.map { e ->
                            EpisodeResponse(
                                id = e.id, podcastId = e.podcastId, title = e.title,
                                description = e.description, audioUrl = e.audioUrl,
                                pubDate = e.pubDate, pubDateTimestamp = e.pubDateTimestamp,
                                duration = e.duration, played = e.played,
                                playbackPosition = e.playbackPosition,
                                downloadPath = e.downloadPath, artworkUrl = existing.artworkUrl,
                            )
                        },
                    ))
                    return@post
                }
                // Fetch from RSS
                val (podcast, episodes) = repository.fetchPodcastPreview(
                    feedUrl = request.feedUrl,
                    collectionId = request.collectionId,
                    artworkUrl = request.artworkUrl,
                    collectionName = request.collectionName,
                    artistName = request.artistName,
                )
                call.respond(PreviewResponse(
                    podcast = PodcastResponse(
                        id = podcast.id,
                        title = podcast.title,
                        author = podcast.author,
                        description = podcast.description,
                        feedUrl = podcast.feedUrl,
                        artworkUrl = podcast.artworkUrl,
                        subscribed = false,
                    ),
                    episodes = episodes.map { e ->
                        EpisodeResponse(
                            id = e.id, podcastId = e.podcastId, title = e.title,
                            description = e.description, audioUrl = e.audioUrl,
                            pubDate = e.pubDate, pubDateTimestamp = e.pubDateTimestamp,
                            duration = e.duration, played = e.played,
                            playbackPosition = e.playbackPosition,
                            downloadPath = e.downloadPath, artworkUrl = podcast.artworkUrl,
                        )
                    },
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Preview failed"))
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

        // GET /api/search-ai?q=... — AI-powered search suggestions
        get("/search-ai") {
            val query = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing query parameter 'q'"))

            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Gemini API key not configured"),
                )
            }

            try {
                val prompt = """Tu es un expert en podcasts. L'utilisateur recherche : "$query"

Suggère 4 podcasts qui correspondent à cette recherche. Les suggestions doivent être des podcasts réels et populaires, en lien direct avec la requête. Privilégie les podcasts en français si la requête est en français, sinon en anglais. Réponds entièrement en français.

Réponds UNIQUEMENT avec un objet JSON valide dans ce format exact, sans markdown, sans blocs de code :
{"intro": "Une courte phrase expliquant tes suggestions par rapport à la recherche", "suggestions": [{"name": "Nom du Podcast", "reason": "Courte raison pour laquelle il correspond à la recherche", "searchQuery": "requête de recherche pour le trouver sur iTunes"}]}"""

                val (text, parsed) = withContext(Dispatchers.IO) {
                    val model = GenerativeModel(
                        modelName = "gemini-2.0-flash",
                        apiKey = apiKey,
                    )

                    val response = model.generateContent(prompt)
                    val rawText = response.text?.trim() ?: ""

                    val cleanJson = rawText
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    Pair(rawText, Json.decodeFromString<AiResponse>(cleanJson))
                }

                call.respond(parsed)
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("AI search failed: $errorMsg"),
                )
            }
        }

        // === Episodes ===

        // GET /api/episodes/:id — get single episode
        get("/episodes/{id}") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val episode = repository.getEpisodeById(episodeId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
            val podcast = repository.getPodcastById(episode.podcastId)
            call.respond(EpisodeResponse(
                id = episode.id,
                podcastId = episode.podcastId,
                title = episode.title,
                description = episode.description,
                audioUrl = episode.audioUrl,
                pubDate = episode.pubDate,
                pubDateTimestamp = episode.pubDateTimestamp,
                duration = episode.duration,
                played = episode.played,
                playbackPosition = episode.playbackPosition,
                downloadPath = episode.downloadPath,
                artworkUrl = podcast?.artworkUrl ?: "",
            ))
        }

        // PUT /api/episodes/:id/position — update playback position
        put("/episodes/{id}/position") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val body = call.receive<PositionUpdate>()
            repository.updatePlaybackPosition(episodeId, body.position)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // PUT /api/episodes/:id/played — mark as played
        put("/episodes/{id}/played") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.markAsPlayed(episodeId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "played"))
        }

        // PUT /api/episodes/:id/unplayed — mark as unplayed
        put("/episodes/{id}/unplayed") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.markAsUnplayed(episodeId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "unplayed"))
        }

        // GET /api/episodes/:id/stream — stream locally downloaded episode
        get("/episodes/{id}/stream") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val episode = repository.getEpisodeById(episodeId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
            val path = episode.downloadPath
            if (path.isNullOrEmpty()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not downloaded locally"))
            }
            val file = File(path)
            if (!file.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Local file not found"))
            }
            call.respondFile(file)
        }

        // === Bookmarks ===

        // GET /api/episodes/:id/bookmarks
        get("/episodes/{id}/bookmarks") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val bookmarks = repository.getBookmarksForEpisode(episodeId).first()
            call.respond(bookmarks.map {
                BookmarkResponse(it.id, it.episodeId, it.positionMs, it.comment, it.createdAt)
            })
        }

        // POST /api/episodes/:id/bookmarks
        post("/episodes/{id}/bookmarks") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val body = call.receive<BookmarkRequest>()
            val id = repository.addBookmark(episodeId, body.positionMs, body.comment)
            call.respond(HttpStatusCode.Created, BookmarkResponse(id, episodeId, body.positionMs, body.comment, System.currentTimeMillis()))
        }

        // DELETE /api/bookmarks/:id
        delete("/bookmarks/{id}") {
            val bookmarkId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid bookmark ID"))
            repository.deleteBookmarkById(bookmarkId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // === Playlist ===

        // GET /api/playlist — get playlist with episode details and artwork
        get("/playlist") {
            val episodes = repository.getPlaylistEpisodesWithArtworkList()
            call.respond(episodes.map { ewa ->
                val podcast = repository.getPodcastById(ewa.episode.podcastId)
                PlaylistItemResponse(
                    id = ewa.episode.id,
                    podcastId = ewa.episode.podcastId,
                    title = ewa.episode.title,
                    description = ewa.episode.description,
                    audioUrl = ewa.episode.audioUrl,
                    pubDate = ewa.episode.pubDate,
                    pubDateTimestamp = ewa.episode.pubDateTimestamp,
                    duration = ewa.episode.duration,
                    played = ewa.episode.played,
                    playbackPosition = ewa.episode.playbackPosition,
                    downloadPath = ewa.episode.downloadPath,
                    artworkUrl = ewa.artworkUrl,
                    podcastTitle = podcast?.title ?: "",
                )
            })
        }

        // POST /api/playlist/:episodeId — add to playlist
        post("/playlist/{episodeId}") {
            val episodeId = call.parameters["episodeId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.addToPlaylist(episodeId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "added"))
        }

        // DELETE /api/playlist/:episodeId — remove from playlist
        delete("/playlist/{episodeId}") {
            val episodeId = call.parameters["episodeId"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.removeFromPlaylist(episodeId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
        }

        // PUT /api/playlist/reorder — reorder playlist
        put("/playlist/reorder") {
            val body = call.receive<ReorderRequest>()
            repository.updatePlaylistPositions(body.episodeIds)
            call.respond(HttpStatusCode.OK, mapOf("status" to "reordered"))
        }

        // DELETE /api/playlist — clear entire playlist
        delete("/playlist") {
            repository.clearPlaylist()
            call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
        }

        // POST /api/playlist/auto-add — auto-add latest episodes (optional ?tagId=...)
        post("/playlist/auto-add") {
            val tagId = call.request.queryParameters["tagId"]?.toLongOrNull()
            if (tagId != null) {
                repository.autoAddLatestEpisodesForTag(tagId)
            } else {
                repository.autoAddLatestEpisodes()
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "auto-added"))
        }

        // === Tags ===

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

                val prompt = """Tu es un expert en recommandation de podcasts. En te basant sur la bibliothèque de podcasts ci-dessous, suggère 6 nouveaux podcasts que l'utilisateur pourrait aimer. Les suggestions doivent être variées mais en lien avec ses centres d'intérêt. Réponds entièrement en français.

Bibliothèque :
$libraryDesc

Réponds UNIQUEMENT avec un objet JSON valide dans ce format exact, sans markdown, sans blocs de code :
{"intro": "Une courte phrase d'introduction personnalisée sur ses goûts", "suggestions": [{"name": "Nom du Podcast", "reason": "Courte raison pour laquelle il aimerait", "searchQuery": "requête de recherche pour le trouver sur iTunes"}]}"""

                val (text, parsed) = withContext(Dispatchers.IO) {
                    val model = GenerativeModel(
                        modelName = "gemini-2.0-flash",
                        apiKey = apiKey,
                    )

                    val response = model.generateContent(prompt)
                    val rawText = response.text?.trim() ?: ""

                    // Parse the JSON response
                    val cleanJson = rawText
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    Pair(rawText, Json.decodeFromString<AiResponse>(cleanJson))
                }

                call.respond(parsed)
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("AI discovery failed: $errorMsg"),
                )
            }
        }

        // GET /api/new-episodes — recent episodes from subscribed podcasts
        get("/new-episodes") {
            try {
                val tagId = call.request.queryParameters["tagId"]?.toLongOrNull()
                val episodes = if (tagId != null) {
                    repository.getRecentEpisodesWithArtworkForTag(tagId).first()
                } else {
                    repository.getRecentEpisodesWithArtwork().first()
                }
                call.respond(episodes.map { ewa ->
                    val podcast = repository.getPodcastById(ewa.episode.podcastId)
                    EpisodeResponse(
                        id = ewa.episode.id,
                        podcastId = ewa.episode.podcastId,
                        title = ewa.episode.title,
                        description = ewa.episode.description,
                        audioUrl = ewa.episode.audioUrl,
                        pubDate = ewa.episode.pubDate,
                        pubDateTimestamp = ewa.episode.pubDateTimestamp,
                        duration = ewa.episode.duration,
                        played = ewa.episode.played,
                        playbackPosition = ewa.episode.playbackPosition,
                        downloadPath = ewa.episode.downloadPath,
                        artworkUrl = ewa.artworkUrl,
                    )
                })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Failed to load new episodes"))
            }
        }

        // GET /api/history — listening history
        get("/history") {
            try {
                val history = repository.getHistoryWithDetails().first()
                call.respond(history.map { h ->
                    HistoryResponse(
                        id = h.history.id,
                        episodeId = h.history.episodeId,
                        podcastId = h.history.podcastId,
                        episodeTitle = h.episodeTitle,
                        podcastTitle = h.podcastTitle,
                        artworkUrl = h.artworkUrl,
                        listenedAt = h.history.listenedAt,
                    )
                })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Failed to load history"))
            }
        }

        // DELETE /api/history — clear listening history
        delete("/history") {
            try {
                repository.clearHistory()
                call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Failed to clear history"))
            }
        }

        // POST /api/history/:episodeId — add history entry
        post("/history/{episodeId}") {
            val episodeId = call.parameters["episodeId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            try {
                val episode = repository.getEpisodeById(episodeId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
                repository.addHistoryEntry(episode.id, episode.podcastId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "added"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Failed to add history"))
            }
        }
    }
}
