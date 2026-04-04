# Podcasto - Agent Context

## Project Overview
Full-featured Android podcast app built from scratch. Allows users to search for podcasts (iTunes API), subscribe to YouTube channels, browse episodes, read descriptions, play audio and video (Media3/ExoPlayer), manage playlists, download episodes offline (including YouTube audio/video), tag subscriptions for filtering, bookmark episodes with comments, track playback progress, filter played/unplayed episodes, and manage everything via an embedded web interface.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Database**: Room (SQLite) — schema version 8 with proper migrations
- **Networking**: Retrofit + OkHttp + iTunes Search API + custom RSS parser
- **Audio/Video**: Media3 (ExoPlayer) with MediaSessionService, MergingMediaSource for video
- **DI**: Hilt
- **Images**: Coil
- **YouTube**: NewPipe Extractor v0.26.0 (full flavor only)
- **Web Server**: Ktor CIO 2.3.12
- **AI**: Google Generative AI (Gemini 2.0 Flash)
- **SSH Tunnel**: JSch (mwiede fork) + localhost.run
- **Backup**: Google Drive API (App Data folder), WorkManager
- **Auth**: Google Sign-In (Play Services Auth)
- **Crash reporting**: Firebase Crashlytics
- **Drag-reorder**: `sh.calvin.reorderable:reorderable:2.4.2`
- **Min SDK**: 26 / **Target & Compile SDK**: 36
- **AGP**: 8.7.3 / **Kotlin**: 2.1.0

## Device & Build
- **Device**: Pixel 7 connected via USB (API 36)
- **ADB**: `$ANDROID_HOME/platform-tools/adb`
- **Build/Deploy**: Use `./run.sh` — `./run.sh [full|store] [debug|release]` (defaults to `full release`). Compiles with `./gradlew assembleFullRelease`, installs via `adb install -r`, launches app.
- **Build Flavors**: `full` (YouTube enabled via NewPipe Extractor) and `store` (YouTube disabled, stub YouTubeExtractor)
- **Source set split**: Real YouTubeExtractor in `src/full/java/`, stub in `src/store/java/`
- **Release signing**: Keystore at `app/podcasto-release.jks` (gitignored), alias `podcasto`, password in `local.properties`
- **Minification**: `isMinifyEnabled = false` and `isShrinkResources = false` in `build.gradle.kts` for dev builds
- **Note**: `compileSdk = 36` requires `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`

## Git Workflow
- Repo: `ghostwan/Podcasto` on GitHub, branch `master`
- After each task/feature batch, build and run on device
- Commit and push after every change/feature

## User Preferences
- User speaks French, respond in French for conversational parts
- Always compile in release mode by default
- DB versioning: proper migrations, NO `fallbackToDestructiveMigration()` (was destroying data)
- Whatever is done on Android must also be done on the web interface

## Database Schema (version 8)
- **PodcastEntity**: id, title, author, description, feedUrl, artworkUrl, subscribed, hidden, sourceType ("rss"/"youtube")
- **EpisodeEntity**: id, podcastId, title, description, audioUrl, pubDate, pubDateTimestamp, duration, downloadPath, videoDownloadPath, played, playbackPosition
- **PlaylistItemEntity**: id, episodeId, position
- **TagEntity**: id, name, position
- **PodcastTagCrossRef**: podcastId, tagId
- **BookmarkEntity**: id, episodeId, positionMs, comment, createdAt
- **HistoryEntity**: id, episodeId, podcastId, listenedAt
- **EpisodeWithArtwork**: Embedded Episode + artworkUrl + sourceType from Podcast
- **HistoryWithDetails**: Embedded HistoryEntity + episodeTitle, podcastTitle, artworkUrl, playbackPosition, duration, played
- Episode ID scheme: `podcast.id * 100000 + index` (deterministic based on podcast ID and episode index in feed)
- Migrations: 2->3 (pubDateTimestamp), 3->4 (hidden), 4->5 (listening_history + sourceType), 5->6 (tags position), 6->7 (downloadPath rename), 7->8 (videoDownloadPath)

## Key Architectural Decisions

### RSS Parser (RssParser.kt)
- `isNamespaceAware = true` means `parser.name` returns local name without prefix
- Must check for `"image"`, `"duration"` etc. (NOT `"itunes:image"`, `"itunes:duration"`)
- Handles CDATA properly, accumulates text across split events
- Supports 10+ date formats for `pubDateTimestamp` parsing
- Supports Atom feeds and `media:content`

### Apple Podcasts Scraper (ApplePodcastsScraper.kt)
- Fallback for podcasts without `feedUrl` in iTunes API (common for Radio France)
- Scrapes Apple Podcasts web page, extracts JSON from `<script id="serialized-server-data">`
- Recursively searches for `"feedUrl"` key in the JSON

### PlayerManager
- Centralized position polling every 500ms while playing (removed duplicate polling from PlayerScreen)
- Saves current position before switching episodes (`saveCurrentPosition()` at start of `play()` and `playMultiple()`)
- Reloads episode from DB before seeking to get latest `playbackPosition`
- On episode completion (`STATE_ENDED`): marks as played, removes from playlist, auto-advances to next
- Custom notification buttons: `SEEK_BACKWARD_10` and `SEEK_FORWARD_30` with i18n display names
- `isVideoMode` in `PlayerState` for YouTube video toggle
- `currentLanguageCode: String?` — preserved across audio/video mode switches
- `toggleVideoMode()` passes language code to maintain audio language when switching modes

### PlaybackService
- `setCustomLayout()` with `CommandButton.Builder` using `ICON_SKIP_BACK_10` and `ICON_SKIP_FORWARD_30`
- Display names use `getString()` for i18n
- `SET_VIDEO_MODE_COMMAND` — handles video mode via `MergingMediaSource` (video-only + audio-only DASH streams)
- Uses `ProgressiveMediaSource` with `DefaultDataSource.Factory()` for local files and `DefaultHttpDataSource.Factory()` for remote URLs

### Navigation
- 3-tab bottom nav: Library (subscriptions), Playlist, New Episodes
- Discover screen accessed via FAB (+) in Library, not a tab
- PlayerScreen rendered as opaque overlay with `BackHandler` for swipe-back gesture
- Settings accessible via overflow menu (3 dots) in Library TopAppBar

### Playlist
- Auto-add: latest unplayed episode per podcast (SQL subquery `WHERE e.pubDateTimestamp = (SELECT MAX(...) WHERE played = 0)`)
- Drag-to-reorder: `longPressDraggableHandle` on drag handle only, `combinedClickable(onLongClick=play)` on content area only (avoids conflict)
- Live progress bars: now-playing episode uses live `playerState.currentPosition/duration`, others use DB values

### Discover Screen
- Country filter chips: All/FR/US/GB/DE/ES/IT/BR/JP
- Checkmark overlay on already-subscribed podcasts
- Auto re-search on country change

### Video Mode (YouTube)
- Single shared `PlayerView` instance reused between inline and fullscreen modes (avoids surface re-attachment crashes)
- `(sharedPlayerView.parent as? ViewGroup)?.removeView(sharedPlayerView)` in each `AndroidView` factory to detach before reattaching
- Fullscreen: landscape orientation + immersive mode (hidden system bars) + auto-hiding controls overlay
- `BackHandler` exits fullscreen first, then closes player
- Web: dual `<video>` + `<audio>` elements with periodic time sync, Fullscreen API for fullscreen mode

### YouTube Downloads
- Audio + Video downloaded as separate streams via NewPipe Extractor
- Download choice dialog shows estimated sizes (HTTP HEAD requests for Content-Length)
- `DownloadMode` enum: `AUDIO`, `VIDEO`, `BOTH`
- When VIDEO mode selected, audio is also downloaded (needed for video playback's audio track)
- YouTube stream URLs are temporary (expire after hours), resolved at download time

### History
- Deduplicated: `deleteHistoryForEpisode(episodeId)` called before `insertHistoryEntry()` — only most recent timestamp kept
- `HistoryWithDetails` includes playbackPosition, duration, played for progress bars

### Progress Bars
- Shown on all episode lists when `playbackPosition > 0` (episode has been started)
- Present in: PodcastDetailScreen, PlaylistScreen, NewEpisodesScreen, HistoryScreen, and web equivalents

### i18n
- `values/strings.xml` (EN default, ~173 strings)
- 9 languages: English, French, German, Spanish, Italian, Dutch, Japanese, Swedish, Chinese (Simplified)
- All screens use `stringResource()`

### App Icon
- Custom vector adaptive icon: headphones with dog face (Podcasto mascot)
- White on Material 3 purple (`#6750A4`)
- Round icon variant via `ic_launcher_round.xml`
- Web favicon matches Android icon design (inline SVG in index.html)

### SharedPreferences
- `"app_settings"` for web server password, Gemini API key, auto-backup, last Drive backup time
- `"player_prefs"` for volume normalization and last episode state

## Important Discoveries / Gotchas

1. **Radio France podcasts have no feedUrl in iTunes API** — must use ApplePodcastsScraper fallback
2. **`fallbackToDestructiveMigration()` destroys all data** — replaced with proper migrations
3. **Episode sorting**: `ORDER BY pubDate DESC` on raw string dates doesn't sort correctly — use `pubDateTimestamp` (epoch millis)
4. **Device locale change for screenshots**: `settings put system system_locales` does NOT work. Must use `cmd locale set-device-locale en-US`
5. **`compileSdk = 36`** requires `android.suppressUnsupportedCompileSdk=36` in gradle.properties with AGP 8.7.3
6. **`playMultiple()` was resetting position to 0**: Fixed to reload episodes from DB for fresh `playbackPosition`
7. **YouTube DASH streams**: video-only + audio-only must be merged via `MergingMediaSource`
8. **Media3 `PlayerView`** can use `MediaController` as its `Player` reference (implements Player interface)
9. **Firebase Crashlytics** auto-initializes via the plugin — no code changes needed beyond gradle config + `google-services.json`
10. **Fullscreen video crash**: Creating separate `PlayerView` instances for inline and fullscreen causes surface re-attachment issues. Must use a single shared `PlayerView` and detach from parent before reattaching
11. **NewPipe AudioStream** has `audioLocale` (Locale) and `averageBitrate`. Video streams have `resolution` (String like "720p"), `width`, `height`
12. **YouTube stream URLs are temporary** (expire after hours) — must be resolved at download time

## Project Structure

```
app/src/main/java/com/ghostwan/podcasto/
├── PodcastoApp.kt                    # Hilt Application class, HiltWorkerFactory
├── MainActivity.kt                    # Single activity
├── PodcastoNavHost.kt                # Navigation (3 tabs + routes)
├── NavHostViewModel.kt               # Shared nav-level ViewModel
├── data/
│   ├── local/
│   │   ├── Entities.kt               # All Room entities + data classes
│   │   ├── Daos.kt                   # All DAOs (podcast, episode, playlist, tag, bookmark, history)
│   │   └── PodcastoDatabase.kt       # Room DB (v8, migrations 2-8)
│   ├── remote/
│   │   ├── ITunesApiService.kt       # Retrofit interface (with country param)
│   │   ├── ITunesModels.kt           # API response models
│   │   ├── RssParser.kt              # RSS/Atom feed parser
│   │   └── ApplePodcastsScraper.kt   # Fallback feedUrl scraper
│   ├── repository/
│   │   └── PodcastRepository.kt      # Single repository (data ops, downloads, stream sizes)
│   └── backup/
│       ├── GoogleDriveBackupManager.kt
│       └── AutoBackupWorker.kt       # WorkManager + Hilt
├── di/
│   └── AppModule.kt                  # Hilt module (DB, API, Repository, PlayerManager, OkHttp)
├── player/
│   ├── PlaybackService.kt            # Media3 MediaSessionService, MergingMediaSource for video
│   └── PlayerManager.kt              # Playback controller + state, video mode, language
├── ui/screens/
│   ├── SubscriptionsScreen.kt        # Library tab (tags, pull-to-refresh, hidden toggle)
│   ├── DiscoverScreen.kt             # Search podcasts (country filter, AI)
│   ├── PodcastDetailScreen.kt        # Episodes list, subscribe, tags, progress bars
│   ├── EpisodeDetailScreen.kt        # Description, actions, bookmarks, download choice dialog
│   ├── PlaylistScreen.kt             # Drag-reorder, auto-add, live progress bars
│   ├── PlayerScreen.kt               # Fullscreen player, shared PlayerView, video mode
│   ├── NewEpisodesScreen.kt          # Latest episodes, tag filter, progress bars
│   ├── HistoryScreen.kt              # Listening history, progress bars
│   └── SettingsScreen.kt             # Gemini key, backup, Google Drive, web server
└── web/
    ├── WebServerService.kt           # Ktor foreground service
    ├── WebRoutes.kt                  # REST API (~1250 lines, all endpoints)
    └── TunnelManager.kt              # SSH tunnel via localhost.run

app/src/full/java/com/ghostwan/podcasto/data/remote/
  YouTubeExtractor.kt  -- Real implementation (stream resolution, video, language, sizes)

app/src/store/java/com/ghostwan/podcasto/data/remote/
  YouTubeExtractor.kt  -- Stub (same interface, throws UnsupportedOperationException)

app/src/main/assets/web/  -- Web UI (HTML/CSS/JS single-page app)
  index.html             -- Main page with inline favicon SVG
  app.js                 -- Application logic (~2925 lines)
  style.css              -- Styles (~1954 lines)
```

## Scripts
- `run.sh` — Build, install, launch (`./run.sh [full|store] [debug|release]`, defaults to `full release`)
- `screenshots.sh` — Switches device to English, captures screens interactively, restores locale
- `portforward.sh` — ADB port forward for web server access via USB

## Resources
- `res/values/strings.xml` — EN default (~173 strings)
- `res/values-{fr,de,es,it,nl,ja,sv,zh-rCN}/strings.xml` — 8 language translations
- `res/drawable/ic_launcher_foreground.xml` — Custom Podcasto mascot icon
- `res/mipmap-anydpi-v26/ic_launcher.xml` — Adaptive icon
- `res/mipmap-anydpi-v26/ic_launcher_round.xml` — Round adaptive icon
- `res/values/ic_launcher_background.xml` — Background color #6750A4
- `res/xml/backup_rules.xml` — Auto backup config
- `res/xml/data_extraction_rules.xml` — Data extraction config
