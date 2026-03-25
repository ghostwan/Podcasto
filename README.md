# Podcasto

A full-featured podcast player for Android, built with Kotlin and Jetpack Compose.

## Features

- **Discover** -- Search for podcasts via the iTunes Search API
- **Subscribe** -- Follow your favorite podcasts and organize them with tags
- **Browse episodes** -- View episode lists sorted by date, filter played/unplayed
- **Playback** -- Stream or play downloaded episodes with Media3 (ExoPlayer), background playback with media notification
- **Playlist** -- Queue episodes, drag-to-reorder (long press), auto-fill with the latest unplayed episode from each subscription (or filtered by tag)
- **Downloads** -- Download episodes for offline listening
- **Bookmarks** -- Bookmark moments in episodes with comments, tap to seek
- **Progress tracking** -- Playback position is saved and restored, episodes are marked as played on completion
- **Apple Podcasts fallback** -- Podcasts without a feed URL in the iTunes API (e.g. Radio France) are resolved by scraping the Apple Podcasts web page

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Database | Room (SQLite) |
| Network | Retrofit, OkHttp |
| Media | Media3 / ExoPlayer |
| Images | Coil |
| Drag & drop | sh.calvin.reorderable |

## Project Structure

```
app/src/main/java/com/music/podcasto/
  data/
    local/         -- Room entities, DAOs, database
    remote/        -- iTunes API service, RSS parser, Apple Podcasts scraper
    repository/    -- PodcastRepository (single source of truth)
  di/              -- Hilt AppModule
  player/          -- PlayerManager, PlaybackService (Media3)
  ui/screens/      -- Compose screens (Discover, Subscriptions, PodcastDetail,
                      EpisodeDetail, Playlist, Player)
  MainActivity.kt
  PodcastoApp.kt   -- Hilt application
  PodcastoNavHost.kt
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
