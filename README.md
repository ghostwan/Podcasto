# Podcasto

A full-featured podcast player for Android with an embedded web management interface, built with Kotlin and Jetpack Compose.

## Features

### Android App
- **Discover** -- Search for podcasts via the iTunes Search API, filter by country (FR, US, GB, DE, ES, IT, BR, JP), AI-powered search suggestions via Gemini (opt-in)
- **AI Discovery** -- Generate personalized podcast recommendations based on your library using Gemini 2.0 Flash
- **Subscribe** -- Follow your favorite podcasts and organize them with custom tags
- **Browse episodes** -- View episode lists sorted by date, filter played/unplayed, now-playing indicator, stale podcast detection (3+ months without new episodes)
- **Playback** -- Stream or play downloaded episodes with Media3 (ExoPlayer), background playback with media notification and custom seek buttons (rewind 10s / forward 30s)
- **Playlist** -- Queue episodes, drag-to-reorder, long press to play directly, auto-fill with the latest unplayed episode from each subscription (or filtered by tag), auto-advance to next episode, live progress bars
- **New Episodes** -- Dedicated screen showing latest episodes from all subscriptions, with tag filtering and played/unplayed toggle
- **Listening History** -- Track all listened episodes with timestamps
- **Downloads** -- Download episodes for offline listening
- **Bookmarks** -- Bookmark moments in episodes with comments, tap to seek
- **Progress tracking** -- Playback position is saved and restored (including when switching episodes or using "Play All"), episodes are marked as played on completion and removed from playlist
- **Apple Podcasts fallback** -- Podcasts without a feed URL in the iTunes API (e.g. Radio France) are resolved by scraping the Apple Podcasts web page
- **Internationalization** -- Full English and French translations

### Web Interface
- **Embedded web server** -- Access your podcast library from any browser on the local network (Ktor CIO)
- **Full library management** -- Browse subscriptions, episodes, subscribe/unsubscribe, tag management
- **Web player** -- Play episodes directly in the browser with progress tracking
- **Playlist management** -- View, reorder (drag & drop), auto-add episodes
- **AI search & discovery** -- Same Gemini-powered features as the Android app
- **Listening history** -- View and track listening history from the web
- **New episodes** -- Browse latest episodes across all subscriptions

## Screenshots

<!-- SCREENSHOTS_START -->
<p align="center">
  <img src="screenshots/01_library.png" width="180" alt="Library" />
  <img src="screenshots/02_discover.png" width="180" alt="Discover" />
  <img src="screenshots/03_podcast_detail.png" width="180" alt="Podcast Detail" />
  <img src="screenshots/04_episode_detail.png" width="180" alt="Episode Detail" />
  <img src="screenshots/05_player.png" width="180" alt="Player" />
  <img src="screenshots/06_playlist.png" width="180" alt="Playlist" />
  <img src="screenshots/07_new_episodes.png" width="180" alt="New Episodes" />
</p>
<!-- SCREENSHOTS_END -->

The app uses a fixed Material 3 purple theme with a three-tab layout (Library, Playlist, New). Discover is accessed via a FAB button in the Library screen. The web server can be started/stopped from the Library toolbar.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room (SQLite) v5 with versioned migrations |
| Network | Retrofit, OkHttp |
| Media | Media3 / ExoPlayer |
| Images | Coil |
| Drag & drop | sh.calvin.reorderable |
| Web Server | Ktor CIO 2.3.12 |
| AI | Google Generative AI (Gemini 2.0 Flash) |

## Project Structure

```
app/src/main/java/com/music/podcasto/
  data/
    local/         -- Room entities, DAOs, database (v5 with migrations)
    remote/        -- iTunes API service, RSS parser, Apple Podcasts scraper
    repository/    -- PodcastRepository (single source of truth)
  di/              -- Hilt AppModule
  player/          -- PlayerManager (position saving, polling, auto-advance),
                      PlaybackService (Media3 with custom notification buttons)
  ui/screens/      -- Compose screens (Discover, Subscriptions, PodcastDetail,
                      EpisodeDetail, Playlist, Player, NewEpisodes, History)
  web/             -- WebServerService, WebRoutes (Ktor REST API)
  MainActivity.kt
  PodcastoApp.kt   -- Hilt application
  PodcastoNavHost.kt

app/src/main/assets/web/  -- Web UI (HTML/CSS/JS single-page app)
app/src/main/res/
  values/          -- English strings (default)
  values-fr/       -- French translations
```

## Building & Running

Requirements: Android SDK, a connected device or emulator (minSdk 26).

```bash
# Build, install, and launch in one step
./run.sh

# Or manually
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.ghostwan.podcasto/.MainActivity
```

### Web Interface

Start the web server from the Library screen toolbar (globe icon). The server URL is displayed in the toolbar and can be copied by tapping it. If your phone and computer are on the same network, access it directly. Otherwise, use USB port forwarding:

```bash
./portforward.sh   # adb forward tcp:8080 tcp:8080
# Then open http://localhost:8080
```

### AI Features

To enable AI-powered search and discovery, you can either:

1. **In-app settings** (recommended): Go to Settings (overflow menu in Library) and enter your Gemini API key
2. **Build-time**: Add your key to `local.properties`:

```properties
GEMINI_API_KEY=your_key_here
```

Get a free API key from [Google AI Studio](https://aistudio.google.com/apikey).

## License

MIT -- see [LICENSE](LICENSE).
