package com.music.podcasto.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PlaylistItemEntity::class,
        TagEntity::class,
        PodcastTagCrossRef::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PodcastoDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun tagDao(): TagDao
}
