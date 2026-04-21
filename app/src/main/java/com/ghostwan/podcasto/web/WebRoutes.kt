package com.ghostwan.podcasto.web

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.ghostwan.podcasto.BuildConfig
import com.ghostwan.podcasto.data.repository.PodcastRepository
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
import java.util.concurrent.ConcurrentHashMap

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
    val hidden: Boolean = false,
    val sourceType: String = "rss",
    val subscribedAt: Long = 0,
)

@Serializable
data class TagResponse(
    val id: Long,
    val name: String,
    val position: Int = 0,
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
    val videoDownloadPath: String? = null,
    val artworkUrl: String = "",
    val sourceType: String = "rss",
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
    val videoDownloadPath: String? = null,
    val artworkUrl: String,
    val podcastTitle: String,
    val sourceType: String = "rss",
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
    val playbackPosition: Long = 0,
    val duration: Long = 0,
    val played: Boolean = false,
)

@Serializable
data class ReorderRequest(
    val episodeIds: List<Long>,
)

@Serializable
data class LoginRequest(
    val password: String,
)

@Serializable
data class LoginResponse(
    val success: Boolean,
    val message: String,
    val remainingAttempts: Int = 0,
)

// Session and rate-limiting management
object WebAuthManager {
    private val sessions = ConcurrentHashMap<String, Long>() // token -> expiry timestamp
    private val failedAttempts = ConcurrentHashMap<String, FailedAttemptInfo>()
    private const val SESSION_DURATION_MS = 24L * 60 * 60 * 1000 // 24 hours
    private const val MAX_ATTEMPTS = 3
    private const val LOCKOUT_DURATION_MS = 15L * 60 * 1000 // 15 minutes

    data class FailedAttemptInfo(val count: Int, val lastAttemptTime: Long)

    fun createSession(): String {
        val token = java.util.UUID.randomUUID().toString()
        sessions[token] = System.currentTimeMillis() + SESSION_DURATION_MS
        return token
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val expiry = sessions[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            sessions.remove(token)
            return false
        }
        return true
    }

    fun isBlocked(ip: String): Boolean {
        val info = failedAttempts[ip] ?: return false
        if (info.count >= MAX_ATTEMPTS) {
            if (System.currentTimeMillis() - info.lastAttemptTime < LOCKOUT_DURATION_MS) {
                return true
            }
            // Lockout expired — reset
            failedAttempts.remove(ip)
            return false
        }
        return false
    }

    fun recordFailedAttempt(ip: String): Int {
        val current = failedAttempts[ip]
        val newCount = if (current != null &&
            System.currentTimeMillis() - current.lastAttemptTime < LOCKOUT_DURATION_MS
        ) {
            current.count + 1
        } else {
            1
        }
        failedAttempts[ip] = FailedAttemptInfo(newCount, System.currentTimeMillis())
        return MAX_ATTEMPTS - newCount
    }

    fun clearFailedAttempts(ip: String) {
        failedAttempts.remove(ip)
    }

    fun clearAllSessions() {
        sessions.clear()
    }
}

fun configureRoutes(context: Context, repository: PodcastRepository) : Routing.() -> Unit = {

    fun getGeminiApiKey(): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val key = prefs.getString("gemini_api_key", null)
        if (!key.isNullOrBlank()) return key
        return BuildConfig.GEMINI_API_KEY
    }

    fun getWebPassword(): String? {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val password = prefs.getString("web_server_password", null)
        return if (password.isNullOrBlank()) null else password
    }

    fun getSessionToken(call: ApplicationCall): String? {
        val cookieHeader = call.request.headers["Cookie"] ?: return null
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("podcasto_session=") }
            ?.substringAfter("podcasto_session=")
    }

    fun getClientIp(call: ApplicationCall): String {
        return call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            ?: call.request.local.remoteAddress
    }

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

    // Auth endpoints (always accessible)
    post("/api/login") {
        val password = getWebPassword()
        if (password == null) {
            // No password set — create session anyway
            val token = WebAuthManager.createSession()
            call.response.headers.append("Set-Cookie", "podcasto_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400")
            call.respond(LoginResponse(success = true, message = "No password required", remainingAttempts = 0))
            return@post
        }

        val ip = getClientIp(call)
        if (WebAuthManager.isBlocked(ip)) {
            call.respond(HttpStatusCode.TooManyRequests, LoginResponse(success = false, message = "Too many attempts. Try again in 15 minutes.", remainingAttempts = 0))
            return@post
        }

        val request = call.receive<LoginRequest>()
        if (request.password == password) {
            WebAuthManager.clearFailedAttempts(ip)
            val token = WebAuthManager.createSession()
            call.response.headers.append("Set-Cookie", "podcasto_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400")
            call.respond(LoginResponse(success = true, message = "Authenticated", remainingAttempts = 0))
        } else {
            val remaining = WebAuthManager.recordFailedAttempt(ip)
            call.respond(HttpStatusCode.Unauthorized, LoginResponse(success = false, message = "Wrong password", remainingAttempts = remaining))
        }
    }

    get("/api/auth-check") {
        val password = getWebPassword()
        if (password == null) {
            call.respond(mapOf("authenticated" to true, "passwordRequired" to false))
            return@get
        }
        val token = getSessionToken(call)
        if (WebAuthManager.isValidSession(token)) {
            call.respond(mapOf("authenticated" to true, "passwordRequired" to true))
        } else {
            call.respond(HttpStatusCode.Unauthorized, mapOf("authenticated" to false, "passwordRequired" to true))
        }
    }

    // API routes — with auth interceptor
    route("/api") {

        // Config endpoint (no auth needed)
        get("/config") {
            call.respond(mapOf("youtubeEnabled" to BuildConfig.YOUTUBE_ENABLED))
        }

        // Auth interceptor: check session on all /api routes (skip login & auth-check)
        intercept(ApplicationCallPipeline.Call) {
            val path = call.request.path()
            if (path == "/api/login" || path == "/api/auth-check" || path == "/api/config") return@intercept

            val password = getWebPassword()
            if (password != null) {
                val token = getSessionToken(call)
                if (!WebAuthManager.isValidSession(token)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
                    finish()
                    return@intercept
                }
            }
        }

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
                    tags = podcastTags.map { TagResponse(it.id, it.name, it.position) },
                    latestEpisodeTimestamp = latestTimestamps[p.id] ?: 0L,
                    hidden = p.hidden,
                    sourceType = p.sourceType,
                    subscribedAt = p.subscribedAt,
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
                tags = podcastTags.map { TagResponse(it.id, it.name, it.position) },
                hidden = podcast.hidden,
                sourceType = podcast.sourceType,
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
                    videoDownloadPath = e.videoDownloadPath,
                    artworkUrl = podcast.artworkUrl,
                    sourceType = podcast.sourceType,
                )
            })
        }

        // GET /api/podcasts/:id/tags — get tags for a podcast
        get("/podcasts/{id}/tags") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val tags = repository.getTagsForPodcast(podcastId).first()
            call.respond(tags.map { TagResponse(it.id, it.name, it.position) })
        }

        // DELETE /api/podcasts/:id — unsubscribe
        delete("/podcasts/{id}") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            repository.unsubscribe(podcastId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "unsubscribed"))
        }

        // PUT /api/podcasts/:id/hidden — toggle hidden state
        put("/podcasts/{id}/hidden") {
            val podcastId = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid podcast ID"))
            val body = call.receive<Map<String, Boolean>>()
            val hidden = body["hidden"] ?: false
            repository.setHidden(podcastId, hidden)
            call.respond(HttpStatusCode.OK, mapOf("status" to if (hidden) "hidden" else "visible"))
        }

        // POST /api/subscribe — subscribe to a podcast
        post("/subscribe") {
            try {
                val request = call.receive<SubscribeRequest>()
                val feedUrl = request.feedUrl.ifEmpty { null }
                val podcast = com.ghostwan.podcasto.data.remote.ITunesPodcast(
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
                            tags = podcastTags.map { TagResponse(it.id, it.name, it.position) },
                            hidden = existing.hidden,
                            sourceType = existing.sourceType,
                        ),
                        episodes = episodes.map { e ->
                            EpisodeResponse(
                                id = e.id, podcastId = e.podcastId, title = e.title,
                                description = e.description, audioUrl = e.audioUrl,
                                pubDate = e.pubDate, pubDateTimestamp = e.pubDateTimestamp,
                                duration = e.duration, played = e.played,
                                playbackPosition = e.playbackPosition,
                                downloadPath = e.downloadPath, videoDownloadPath = e.videoDownloadPath,
                                artworkUrl = existing.artworkUrl,
                                sourceType = existing.sourceType,
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
                            downloadPath = e.downloadPath, videoDownloadPath = e.videoDownloadPath,
                            artworkUrl = podcast.artworkUrl,
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

            val apiKey = getGeminiApiKey()
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

                val (_, parsed) = withContext(Dispatchers.IO) {
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
                videoDownloadPath = episode.videoDownloadPath,
                artworkUrl = podcast?.artworkUrl ?: "",
                sourceType = podcast?.sourceType ?: "rss",
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

        // GET /api/episodes/:id/stream — stream locally downloaded episode (audio)
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

        // GET /api/episodes/:id/stream-video — stream locally downloaded video file
        get("/episodes/{id}/stream-video") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            val episode = repository.getEpisodeById(episodeId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
            val path = episode.videoDownloadPath
            if (path.isNullOrEmpty()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Video not downloaded locally"))
            }
            val file = File(path)
            if (!file.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Local video file not found"))
            }
            call.respondFile(file)
        }

        // DELETE /api/episodes/:id/download — delete downloaded episode file
        delete("/episodes/{id}/download") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.deleteDownload(episodeId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
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
            val hiddenIds = repository.getHiddenPodcastIds().first()
            val episodes = repository.getPlaylistEpisodesWithArtworkList()
                .filter { it.episode.podcastId !in hiddenIds }
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
                    videoDownloadPath = ewa.episode.videoDownloadPath,
                    artworkUrl = ewa.artworkUrl,
                    podcastTitle = podcast?.title ?: "",
                    sourceType = podcast?.sourceType ?: "rss",
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

        // POST /api/playlist/:episodeId/top — add to top of playlist
        post("/playlist/{episodeId}/top") {
            val episodeId = call.parameters["episodeId"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            repository.addToPlaylistTop(episodeId)
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
            call.respond(tags.map { TagResponse(it.id, it.name, it.position) })
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

        // PUT /api/tags/reorder — reorder tags by position
        put("/tags/reorder") {
            val body = call.receive<Map<String, List<Long>>>()
            val tagIds = body["tagIds"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'tagIds'"))
            repository.updateTagPositions(tagIds)
            call.respond(HttpStatusCode.OK, mapOf("status" to "reordered"))
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
            val apiKey = getGeminiApiKey()
            if (apiKey.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Gemini API key not configured. Configure it in Settings."),
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

                val (_, parsed) = withContext(Dispatchers.IO) {
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
                val hiddenIds = repository.getHiddenPodcastIds().first()
                val episodes = if (tagId != null) {
                    repository.getRecentEpisodesWithArtworkForTag(tagId).first()
                } else {
                    repository.getRecentEpisodesWithArtwork().first()
                }.filter { it.episode.podcastId !in hiddenIds }
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
                        videoDownloadPath = ewa.episode.videoDownloadPath,
                        artworkUrl = ewa.artworkUrl,
                        sourceType = podcast?.sourceType ?: "rss",
                    )
                })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Failed to load new episodes"))
            }
        }

        // GET /api/history — listening history
        get("/history") {
            try {
                val hiddenIds = repository.getHiddenPodcastIds().first()
                val history = repository.getHistoryWithDetails().first()
                    .filter { it.history.podcastId !in hiddenIds }
                call.respond(history.map { h ->
                    HistoryResponse(
                        id = h.history.id,
                        episodeId = h.history.episodeId,
                        podcastId = h.history.podcastId,
                        episodeTitle = h.episodeTitle,
                        podcastTitle = h.podcastTitle,
                        artworkUrl = h.artworkUrl,
                        listenedAt = h.history.listenedAt,
                        playbackPosition = h.playbackPosition,
                        duration = h.duration,
                        played = h.played,
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

        // === YouTube ===

        // GET /api/youtube/languages?url=... — get available audio languages for a YouTube video
        // Returns {languages: {code: displayName}, defaultAudioUrl?: string}
        // When only 1 language, returns defaultAudioUrl to avoid a second resolve call
        get("/youtube/languages") {
            if (!BuildConfig.YOUTUBE_ENABLED) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("YouTube is not available in this build"))
                return@get
            }
            val videoUrl = call.request.queryParameters["url"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' parameter"))
            try {
                if (!com.ghostwan.podcasto.data.remote.YouTubeExtractor.isYouTubeVideoUrl(videoUrl)) {
                    call.respond(mapOf("languages" to emptyMap<String, String>(), "defaultAudioUrl" to videoUrl))
                    return@get
                }
                val episode = com.ghostwan.podcasto.data.local.EpisodeEntity(
                    id = 0, podcastId = 0, title = "", description = "",
                    audioUrl = videoUrl, pubDate = "", pubDateTimestamp = 0,
                )
                val options = repository.getAvailableLanguages(episode)
                if (options == null || options.availableLanguages.size <= 1) {
                    call.respond(mapOf(
                        "languages" to emptyMap<String, String>(),
                        "defaultAudioUrl" to (options?.defaultAudioUrl ?: ""),
                    ))
                } else {
                    call.respond(mapOf(
                        "languages" to options.availableLanguages,
                        "originalLanguageCode" to (options.originalLanguageCode ?: ""),
                    ))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get languages: ${e.message}"))
            }
        }

        // GET /api/youtube/resolve?url=...&lang=xx — resolve YouTube video to audio stream URL (optionally for a specific language)
        get("/youtube/resolve") {
            if (!BuildConfig.YOUTUBE_ENABLED) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("YouTube is not available in this build"))
                return@get
            }
            val videoUrl = call.request.queryParameters["url"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' parameter"))
            val lang = call.request.queryParameters["lang"]
            try {
                // For episodes already in DB, use resolveAudioUrl which checks sourceType
                // For direct URL resolution, check if it's a YouTube URL
                if (!com.ghostwan.podcasto.data.remote.YouTubeExtractor.isYouTubeVideoUrl(videoUrl)) {
                    call.respond(mapOf("audioUrl" to videoUrl))
                    return@get
                }
                val episode = com.ghostwan.podcasto.data.local.EpisodeEntity(
                    id = 0, podcastId = 0, title = "", description = "",
                    audioUrl = videoUrl, pubDate = "", pubDateTimestamp = 0,
                )
                val resolved = if (lang != null) {
                    repository.resolveAudioUrlForLanguage(episode, lang)
                } else {
                    repository.resolveAudioUrl(episode)
                }
                call.respond(mapOf("audioUrl" to resolved.url))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resolve YouTube audio: ${e.message}"))
            }
        }

        // GET /api/youtube/resolve-video?url=... — resolve YouTube video to separate video + audio stream URLs
        get("/youtube/resolve-video") {
            if (!BuildConfig.YOUTUBE_ENABLED) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("YouTube is not available in this build"))
                return@get
            }
            val videoUrl = call.request.queryParameters["url"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' parameter"))
            try {
                if (!com.ghostwan.podcasto.data.remote.YouTubeExtractor.isYouTubeVideoUrl(videoUrl)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Not a YouTube URL"))
                    return@get
                }
                val episode = com.ghostwan.podcasto.data.local.EpisodeEntity(
                    id = 0, podcastId = 0, title = "", description = "",
                    audioUrl = videoUrl, pubDate = "", pubDateTimestamp = 0,
                )
                val videoStream = repository.resolveVideoUrl(episode)
                if (videoStream != null) {
                    call.respond(mapOf(
                        "videoUrl" to videoStream.videoUrl,
                        "audioUrl" to videoStream.audioUrl,
                        "durationSeconds" to videoStream.durationSeconds.toString(),
                        "width" to videoStream.width.toString(),
                        "height" to videoStream.height.toString(),
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No video stream available"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to resolve YouTube video: ${e.message}"))
            }
        }

        // GET /api/youtube/stream-sizes?episodeId=... — get audio and video stream sizes for download choice
        get("/youtube/stream-sizes") {
            if (!BuildConfig.YOUTUBE_ENABLED) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("YouTube is not available in this build"))
                return@get
            }
            val episodeId = call.request.queryParameters["episodeId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'episodeId' parameter"))
            try {
                val episode = repository.getEpisodeById(episodeId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
                val sizes = repository.getStreamSizes(episode)
                if (sizes != null) {
                    call.respond(mapOf(
                        "audioSize" to sizes.audioSize.toString(),
                        "videoSize" to sizes.videoSize.toString(),
                        "videoResolution" to sizes.videoResolution,
                    ))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Not a YouTube episode"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to get stream sizes: ${e.message}"))
            }
        }

        // POST /api/episodes/:id/download — download episode with mode (audio/video/both)
        post("/episodes/{id}/download") {
            val episodeId = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid episode ID"))
            try {
                val episode = repository.getEpisodeById(episodeId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Episode not found"))
                val body = try { call.receive<Map<String, String>>() } catch (_: Exception) { emptyMap() }
                val modeStr = body["mode"] ?: "both"
                val mode = when (modeStr) {
                    "audio" -> PodcastRepository.DownloadMode.AUDIO
                    "video" -> PodcastRepository.DownloadMode.VIDEO
                    else -> PodcastRepository.DownloadMode.BOTH
                }
                repository.downloadEpisode(episode, mode)
                call.respond(mapOf("status" to "ok"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Download failed: ${e.message}"))
            }
        }

        // POST /api/youtube/subscribe — subscribe to YouTube channel
        post("/youtube/subscribe") {
            if (!BuildConfig.YOUTUBE_ENABLED) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("YouTube is not available in this build"))
                return@post
            }
            try {
                val body = call.receive<Map<String, String>>()
                val channelUrl = body["channelUrl"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'channelUrl'"))
                val podcast = repository.subscribeToYouTubeChannel(channelUrl)
                call.respond(HttpStatusCode.OK, mapOf(
                    "status" to "subscribed",
                    "id" to podcast.id.toString(),
                    "name" to podcast.title,
                    "artistName" to podcast.author,
                    "artworkUrl" to podcast.artworkUrl,
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("YouTube subscribe failed: ${e.message}"))
            }
        }

        // === Settings ===

        // GET /api/settings/gemini-key — check if Gemini key is configured
        get("/settings/gemini-key") {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val key = prefs.getString("gemini_api_key", null)
            val hasUserKey = !key.isNullOrBlank()
            val hasBuildConfigKey = try { BuildConfig.GEMINI_API_KEY.isNotEmpty() } catch (_: Exception) { false }
            call.respond(mapOf(
                "configured" to (hasUserKey || hasBuildConfigKey),
                "source" to if (hasUserKey) "user" else if (hasBuildConfigKey) "builtin" else "none",
            ))
        }

        // PUT /api/settings/gemini-key — set Gemini API key
        put("/settings/gemini-key") {
            try {
                val body = call.receiveText()
                val json = Json.decodeFromString<Map<String, String>>(body)
                val newKey = json["key"] ?: ""
                val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                if (newKey.isBlank()) {
                    prefs.edit().remove("gemini_api_key").apply()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
                } else {
                    prefs.edit().putString("gemini_api_key", newKey).apply()
                    call.respond(HttpStatusCode.OK, mapOf("status" to "saved"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
            }
        }

        // GET /api/backup — export all data as JSON
        get("/backup") {
            try {
                val json = repository.exportBackup()
                call.respondText(json, ContentType.Application.Json)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Export failed"))
            }
        }

        // POST /api/restore — import data from JSON
        post("/restore") {
            try {
                val body = call.receiveText()
                repository.importBackup(body)
                call.respond(HttpStatusCode.OK, mapOf("status" to "restored"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Import failed"))
            }
        }
    }
}
