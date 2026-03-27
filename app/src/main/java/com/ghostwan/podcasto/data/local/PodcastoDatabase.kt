package com.ghostwan.podcasto.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PlaylistItemEntity::class,
        TagEntity::class,
        PodcastTagCrossRef::class,
        BookmarkEntity::class,
        HistoryEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class PodcastoDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun tagDao(): TagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
}
