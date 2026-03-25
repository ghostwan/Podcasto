package com.music.podcasto.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val author: String,
    val description: String,
    val feedUrl: String,
    val artworkUrl: String,
    val subscribed: Boolean = false,
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val title: String,
    val description: String,
    val audioUrl: String,
    val pubDate: String,
    val duration: Long = 0,
    val downloadPath: String? = null,
)

@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val position: Int,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(tableName = "podcast_tag_cross_ref", primaryKeys = ["podcastId", "tagId"])
data class PodcastTagCrossRef(
    val podcastId: Long,
    val tagId: Long,
)
