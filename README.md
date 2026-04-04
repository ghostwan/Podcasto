<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" alt="Podcasto icon" />
</p>

# Podcasto

A full-featured podcast player for Android with YouTube channel support, an embedded web management interface, and AI-powered discovery -- built with Kotlin and Jetpack Compose.

## Features

### Android App
- **Discover** -- Search for podcasts via the iTunes Search API, filter by country (FR, US, GB, DE, ES, IT, BR, JP), AI-powered search suggestions via Gemini (opt-in)
- **AI Discovery** -- Generate personalized podcast recommendations based on your library using Gemini 2.0 Flash
- **YouTube Channels** -- Subscribe to YouTube channels as audio podcasts, with multi-language audio track selection (full flavor only)
- **Subscribe** -- Follow your favorite podcasts and organize them with custom tags
- **Browse episodes** -- View episode lists sorted by date, filter played/unplayed, now-playing indicator, stale podcast detection (3+ months without new episodes)
- **Playback** -- Stream or play downloaded episodes with Media3 (ExoPlayer), background playback with media notification and custom seek buttons (rewind 10s / forward 30s), volume normalization (LoudnessEnhancer)
- **Playlist** -- Queue episodes, drag-to-reorder, long press to play directly, auto-fill with the latest unplayed episode from each subscription (or filtered by tag), auto-advance to next episode, live progress bars
- **New Episodes** -- Dedicated screen showing latest episodes from all subscriptions, with tag filtering and played/unplayed toggle
- **Listening History** -- Track all listened episodes with timestamps
- **Downloads** -- Download episodes for offline listening, delete downloaded files
- **Bookmarks** -- Bookmark moments in episodes with comments, tap to seek
- **Progress tracking** -- Playback position is saved and restored (including when switching episodes or using "Play All"), episodes are marked as played on completion and removed from playlist
- **Hidden podcasts** -- Long press on a subscription to hide it from the library (toggle visibility with the eye icon)
- **Backup & Restore** -- Export all data (podcasts, episodes, tags, bookmarks, playlist, history, settings) to a local JSON file, or import from a previous backup. Google Drive backup with optional auto-backup every 24h via WorkManager
- **Settings** -- Configure Gemini API key, Google Drive backup, volume normalization, web server password, and version info (long press to open GitHub) from the overflow menu
- **SSH Tunnel** -- Expose the web server to the internet via localhost.run (JSch SSH tunnel, no signup required), accessible from a unified server button with Local/Tunnel mode selection
- **Apple Podcasts fallback** -- Podcasts without a feed URL in the iTunes API (e.g. Radio France) are resolved by scraping the Apple Podcasts web page
- **Internationalization** -- Full English, French, German, Spanish, Italian, Dutch, Japanese, Swedish, and Chinese (Simplified) translations

### Web Interface
- **Embedded web server** -- Access your podcast library from any browser on the local network (Ktor CIO)
- **Full library management** -- Browse subscriptions, episodes, subscribe/unsubscribe, tag management, hidden podcasts toggle
- **Web player** -- Play episodes directly in the browser with progress tracking, volume normalization (DynamicsCompressor)
- **Playlist management** -- View, reorder (drag & drop), auto-add episodes
- **YouTube support** -- Subscribe to YouTube channels, multi-language audio selection (when YouTube is enabled)
- **AI search & discovery** -- Same Gemini-powered features as the Android app
- **Listening history** -- View and track listening history from the web
- **New episodes** -- Browse latest episodes across all subscriptions
- **Backup & Restore** -- Export/import all data as JSON directly from the web settings
- **Settings** -- Configure Gemini API key from the web interface
- **Password protection** -- Optional password authentication with rate limiting (3 attempts, 15-minute lockout), session-based via cookie

## Screenshots

### Android App

<!-- SCREENSHOTS_ANDROID_START -->
<p align="center">
  <img src="screenshots/android/01_library.png" width="180" alt="Library" />
  <img src="screenshots/android/02_new.png" width="180" alt="New" />
  <img src="screenshots/android/03_playlist.png" width="180" alt="Playlist" />
  <img src="screenshots/android/03_settings.png" width="180" alt="Settings" />
  <img src="screenshots/android/04_player.png" width="180" alt="Player" />
  <img src="screenshots/android/05_history.png" width="180" alt="History" />
  <img src="screenshots/android/05_podcast_detail.png" width="180" alt="Podcast detail" />
  <img src="screenshots/android/06_details.png" width="180" alt="Details" />
  <img src="screenshots/android/06_tags.png" width="180" alt="Tags" />
  <img src="screenshots/android/07_search.png" width="180" alt="Search" />
  <img src="screenshots/android/08_server.png" width="180" alt="Server" />
</p>
<!-- SCREENSHOTS_ANDROID_END -->

### Web Interface

<!-- SCREENSHOTS_WEB_START -->
<p align="center">
  <img src="screenshots/web/11_web_playlist.png" width="400" alt="Web playlist" />
  <img src="screenshots/web/12_web_history.png" width="400" alt="Web history" />
  <img src="screenshots/web/13_web_search.png" width="400" alt="Web search" />
  <img src="screenshots/web/14_web_ai_discovery.png" width="400" alt="Web ai discovery" />
</p>
<!-- SCREENSHOTS_WEB_END -->

The app uses a fixed Material 3 purple theme with a three-tab layout (Library, Playlist, New). Discover is accessed via a FAB button in the Library screen. The web server can be started/stopped from the Library toolbar.

## Build Flavors

Podcasto ships with two build flavors:

| Flavor | YouTube | NewPipe Extractor | Play Store safe |
|--------|---------|-------------------|-----------------|
| **`store`** | Disabled | Not included | Yes |
| **`full`** | Enabled | Included | No (ToS risk) |

- **`full`** (default) -- Includes YouTube channel subscription via NewPipe Extractor for audio stream resolution. Suitable for direct distribution (GitHub, sideloading).
- **`store`** -- Excludes NewPipe Extractor entirely from the binary. All YouTube UI and API paths are gated behind `BuildConfig.YOUTUBE_ENABLED`. Safe for Google Play Store submission.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room (SQLite) v7 with versioned migrations |
| Network | Retrofit, OkHttp |
| Media | Media3 / ExoPlayer |
| Images | Coil |
| YouTube | NewPipe Extractor v0.26.0 (full flavor only) |
| Drag & drop | sh.calvin.reorderable |
| Web Server | Ktor CIO 2.3.12 |
| AI | Google Generative AI (Gemini 2.0 Flash) |
| SSH Tunnel | JSch (mwiede fork) + localhost.run |
| Backup | Google Drive API (App Data folder), WorkManager |
| Auth | Google Sign-In (Play Services Auth) |

## Project Structure

```
app/src/main/java/com/ghostwan/podcasto/
  data/
    local/         -- Room entities, DAOs, database (v7 with migrations)
    remote/        -- iTunes API service, RSS parser, Apple Podcasts scraper
    repository/    -- PodcastRepository (single source of truth, backup export/import)
    backup/        -- GoogleDriveBackupManager, AutoBackupWorker (WorkManager + Hilt)
  di/              -- Hilt AppModule
  player/          -- PlayerManager (position saving, polling, auto-advance, volume normalization),
                      PlaybackService (Media3 with custom notification buttons, LoudnessEnhancer)
  ui/screens/      -- Compose screens (Discover, Subscriptions, PodcastDetail,
                      EpisodeDetail, Playlist, Player, NewEpisodes, History, Settings)
  web/             -- WebServerService, WebRoutes (Ktor REST API), TunnelManager (SSH tunnel)
  MainActivity.kt
  PodcastoApp.kt   -- Hilt application, HiltWorkerFactory integration
  PodcastoNavHost.kt
  NavHostViewModel.kt

app/src/full/java/com/ghostwan/podcasto/data/remote/
  YouTubeExtractor.kt  -- Real implementation using NewPipe Extractor

app/src/store/java/com/ghostwan/podcasto/data/remote/
  YouTubeExtractor.kt  -- Stub (same interface, methods throw UnsupportedOperationException)

app/src/main/assets/web/  -- Web UI (HTML/CSS/JS single-page app)
app/src/main/res/
  values/          -- English strings (default)
  values-fr/       -- French translations
  values-de/       -- German translations
  values-es/       -- Spanish translations
  values-it/       -- Italian translations
  values-nl/       -- Dutch translations
  values-ja/       -- Japanese translations
  values-sv/       -- Swedish translations
  values-zh-rCN/   -- Chinese (Simplified) translations
```

## Building & Running

Requirements: Android SDK, a connected device or emulator (minSdk 26).

```bash
# Build, install, and launch (full flavor, release mode)
./run.sh

# Specify flavor and build type
./run.sh full release    # default
./run.sh store release   # Play Store variant (no YouTube)
./run.sh full debug      # debug build with YouTube

# Or manually
./gradlew assembleFullRelease
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
adb shell am start -n com.ghostwan.podcasto/.MainActivity
```

Version is configured in `gradle.properties`:
```properties
APP_VERSION_NAME=1.0.0
APP_VERSION_CODE=1
```

### Web Interface

Start the web server from the Library screen toolbar (globe icon). The server URL is displayed in the toolbar and can be copied by tapping it. If your phone and computer are on the same network, access it directly. Otherwise, use USB port forwarding:

```bash
./portforward.sh   # adb forward tcp:8080 tcp:8080
# Then open http://localhost:8080
```

#### SSH Tunnel (Remote Access)

You can also expose the web server to the internet via an SSH tunnel (localhost.run). Toggle the tunnel from the cloud icon in the Library toolbar. The generated public URL (e.g. `https://xxxxx.lhr.life`) can be shared to access the web interface from anywhere -- no signup or configuration required.

### AI Features

To enable AI-powered search and discovery, you can either:

1. **In-app settings** (recommended): Go to Settings (overflow menu in Library) and enter your Gemini API key
2. **Build-time**: Add your key to `local.properties`:

```properties
GEMINI_API_KEY=your_key_here
```

Get a free API key from [Google AI Studio](https://aistudio.google.com/apikey).

### Backup & Restore

**Local backup**: From Settings, export all your data to a JSON file in Downloads, or import from a previously exported file. Also available from the web interface settings.

**Google Drive backup**: Sign in with your Google account in Settings to back up to Google Drive's App Data folder. Enable auto-backup for automatic daily backups. Requires a configured OAuth client ID in Google Cloud Console (Android type, with your app's package name and signing certificate SHA-1).

## License

MIT -- see [LICENSE](LICENSE).
