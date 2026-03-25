# Podcasto - Agent Context

## Project Overview
Full-featured Android podcast app built from scratch. Allows users to search for podcasts (iTunes API), subscribe, browse episodes, read descriptions, play audio (Media3/ExoPlayer), manage playlists, download episodes offline, tag subscriptions for filtering, bookmark episodes with comments, track playback progress, and filter played/unplayed episodes.

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Database**: Room (SQLite) — schema version 3 with proper migrations
- **Networking**: Retrofit + iTunes Search API + custom RSS parser
- **Audio**: Media3 (ExoPlayer) with MediaSessionService
- **DI**: Hilt
- **Images**: Coil
- **Drag-reorder**: `sh.calvin.reorderable:reorderable:2.4.2`
- **Min SDK**: 26 / **Target & Compile SDK**: 36
- **AGP**: 8.7.3 / **Kotlin**: 2.1.0

## Device & Build
- **Device**: Pixel 7 connected via USB (API 36)
- **ADB**: `$ANDROID_HOME/platform-tools/adb`
- **Build/Deploy**: Use `./run.sh` — defaults to `release`, accepts `debug` argument. Compiles with `./gradlew assembleRelease` (or `assembleDebug`), installs via `adb install -r`, launches app.
- **Release signing**: Uses debug signing config (`signingConfig = signingConfigs.getByName("debug")`) for easy install without a release keystore.
- **Note**: `compileSdk = 36` requires `android.suppressUnsupportedCompileSdk=36` in `gradle.properties`

## Git Workflow
- Repo: `ghostwan/Podcasto` on GitHub, branch `master`
- After each task/feature batch, build and run on device
- Only commit and push when user confirms it works

## User Preferences
- User speaks French, respond in French for conversational parts
- Always compile in release mode by default
- DB versioning: proper migrations, NO `fallbackToDestructiveMigration()` (was destroying data)

## Database Schema (version 3)
- **PodcastEntity**: id, name, artistName, artworkUrl, feedUrl, description, subscribed, lastUpdated
- **EpisodeEntity**: id, podcastId, title, description, audioUrl, pubDate, pubDateTimestamp, duration, imageUrl, played, playbackPosition, localFilePath
- **PlaylistItemEntity**: id, episodeId, position
- **TagEntity**: id, name
- **PodcastTagCrossRef**: podcastId, tagId
- **BookmarkEntity**: id, episodeId, positionMs, comment, createdAt
- **EpisodeWithArtwork**: Embedded Episode + Relation to Podcast (for artwork)
- Episode ID scheme: `podcast.id * 100000 + index` (deterministic based on podcast ID and episode index in feed)
- Migration 2->3: adds `pubDateTimestamp` column

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

### PlaybackService
- `setCustomLayout()` with `CommandButton.Builder` using `ICON_SKIP_BACK_10` and `ICON_SKIP_FORWARD_30`
- Display names use `getString()` for i18n

### Navigation
- 2-tab bottom nav: Library (subscriptions), Playlist
- Discover screen accessed via FAB (+) in Library, not a tab
- PlayerScreen rendered as opaque overlay with `BackHandler` for swipe-back gesture

### Playlist
- Auto-add: latest unplayed episode per podcast (SQL subquery `WHERE e.pubDateTimestamp = (SELECT MAX(...) WHERE played = 0)`)
- Drag-to-reorder: `longPressDraggableHandle` on drag handle only, `combinedClickable(onLongClick=play)` on content area only (avoids conflict)
- Live progress bars: now-playing episode uses live `playerState.currentPosition/duration`, others use DB values

### Discover Screen
- Country filter chips: All/FR/US/GB/DE/ES/IT/BR/JP
- Checkmark overlay on already-subscribed podcasts
- Auto re-search on country change

### i18n
- `values/strings.xml` (EN default, ~65 strings)
- `values-fr/strings.xml` (French translations)
- All screens use `stringResource()`

### App Icon
- Custom vector adaptive icon: headphones with microphone and sound waves
- White on Material 3 purple (`#6750A4`)
- Round icon variant via `ic_launcher_round.xml`

## Important Discoveries / Gotchas

1. **Radio France podcasts have no feedUrl in iTunes API** — must use ApplePodcastsScraper fallback
2. **`fallbackToDestructiveMigration()` destroys all data** — replaced with proper `Migration(2, 3)`
3. **Episode sorting**: `ORDER BY pubDate DESC` on raw string dates doesn't sort correctly — use `pubDateTimestamp` (epoch millis)
4. **Device locale change for screenshots**: `settings put system system_locales` does NOT work. Must use `cmd locale set-device-locale en-US`
5. **`compileSdk = 36`** requires `android.suppressUnsupportedCompileSdk=36` in gradle.properties with AGP 8.7.3
6. **`playMultiple()` was resetting position to 0**: `controller?.setMediaItems(mediaItems, startIndex, 0)` hardcoded `startPositionMs=0`. Fixed to reload episodes from DB for fresh `playbackPosition`.

## Project Structure

```
app/src/main/java/com/music/podcasto/
├── PodcastoApp.kt                    # Hilt Application class
├── MainActivity.kt                    # Single activity
├── PodcastoNavHost.kt                # Navigation (2 tabs + routes)
├── NavHostViewModel.kt               # Shared nav-level ViewModel
├── data/
│   ├── local/
│   │   ├── Entities.kt               # All Room entities
│   │   ├── Daos.kt                   # All DAOs
│   │   └── PodcastoDatabase.kt       # Room DB (v3)
│   ├── remote/
│   │   ├── ITunesApiService.kt       # Retrofit interface (with country param)
│   │   ├── ITunesModels.kt           # API response models
│   │   ├── RssParser.kt              # RSS/Atom feed parser
│   │   └── ApplePodcastsScraper.kt   # Fallback feedUrl scraper
│   └── repository/
│       └── PodcastRepository.kt      # Single repository for all data ops
├── di/
│   └── AppModule.kt                  # Hilt module (DB, API, Repository, PlayerManager)
├── player/
│   ├── PlaybackService.kt            # Media3 MediaSessionService
│   └── PlayerManager.kt              # Playback controller + state
└── ui/screens/
    ├── SubscriptionsScreen.kt        # Library tab (tags, pull-to-refresh)
    ├── DiscoverScreen.kt             # Search podcasts (country filter)
    ├── PodcastDetailScreen.kt        # Episodes list, subscribe, tags
    ├── EpisodeDetailScreen.kt        # Description, actions, bookmarks
    ├── PlaylistScreen.kt             # Drag-reorder, auto-add, live progress
    └── PlayerScreen.kt               # Fullscreen player overlay
```

## Scripts
- `run.sh` — Build, install, launch (defaults to release, accepts `debug` arg)
- `screenshots.sh` — Switches device to English, captures 6 screens interactively, restores locale

## Resources
- `res/values/strings.xml` — EN default (~65 strings)
- `res/values-fr/strings.xml` — French translations
- `res/drawable/ic_launcher_foreground.xml` — Custom headphones+mic+waves icon
- `res/mipmap-anydpi-v26/ic_launcher.xml` — Adaptive icon
- `res/mipmap-anydpi-v26/ic_launcher_round.xml` — Round adaptive icon
- `res/values/ic_launcher_background.xml` — Background color #6750A4
- `res/xml/backup_rules.xml` — Auto backup config
- `res/xml/data_extraction_rules.xml` — Data extraction config
