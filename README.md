# Podcasto

A full-featured podcast player for Android, built with Kotlin and Jetpack Compose.

## Features

- **Discover** -- Search for podcasts via the iTunes Search API, filter by country/market (FR, US, GB, DE, ES, IT, BR, JP), see which podcasts you're already subscribed to
- **Subscribe** -- Follow your favorite podcasts and organize them with tags
- **Browse episodes** -- View episode lists sorted by date, filter played/unplayed, now-playing indicator
- **Playback** -- Stream or play downloaded episodes with Media3 (ExoPlayer), background playback with media notification and custom seek buttons (rewind 10s / forward 30s)
- **Playlist** -- Queue episodes, drag-to-reorder (long press on handle), long press on episode to play directly, auto-fill with the latest unplayed episode from each subscription (or filtered by tag), auto-advance to next episode on completion, live progress bars
- **Downloads** -- Download episodes for offline listening
- **Bookmarks** -- Bookmark moments in episodes with comments, tap to seek
- **Progress tracking** -- Playback position is saved and restored (including when switching episodes or using "Play All"), episodes are marked as played on completion and removed from playlist
- **Apple Podcasts fallback** -- Podcasts without a feed URL in the iTunes API (e.g. Radio France) are resolved by scraping the Apple Podcasts web page
- **Internationalization** -- Full English and French translations

## Screenshots

The app uses Material 3 dynamic theming with a two-tab layout (Library + Playlist). Discover is accessed via a FAB button in the Library screen.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room (SQLite) with versioned migrations |
| Network | Retrofit, OkHttp |
| Media | Media3 / ExoPlayer |
| Images | Coil |
| Drag & drop | sh.calvin.reorderable |

## Project Structure

```
app/src/main/java/com/music/podcasto/
  data/
    local/         -- Room entities, DAOs, database (v3 with migrations)
    remote/        -- iTunes API service, RSS parser, Apple Podcasts scraper
    repository/    -- PodcastRepository (single source of truth)
  di/              -- Hilt AppModule
  player/          -- PlayerManager (position saving, polling, auto-advance),
                      PlaybackService (Media3 with custom notification buttons)
  ui/screens/      -- Compose screens (Discover, Subscriptions, PodcastDetail,
                      EpisodeDetail, Playlist, Player)
  MainActivity.kt
  PodcastoApp.kt   -- Hilt application
  PodcastoNavHost.kt

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
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.music.podcasto/.MainActivity
```

## License

MIT -- see [LICENSE](LICENSE).
