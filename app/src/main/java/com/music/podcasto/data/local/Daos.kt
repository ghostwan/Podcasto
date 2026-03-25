package com.music.podcasto.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY title ASC")
    fun getSubscribedPodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getPodcastById(id: Long): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: PodcastEntity)

    @Query("UPDATE podcasts SET subscribed = :subscribed WHERE id = :id")
    suspend fun updateSubscription(id: Long, subscribed: Boolean)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcast(id: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun getEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeById(id: Long): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("UPDATE episodes SET downloadPath = :path WHERE id = :id")
    suspend fun updateDownloadPath(id: Long, path: String?)

    @Query("SELECT * FROM episodes WHERE downloadPath IS NOT NULL")
    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>
}

@Dao
interface PlaylistDao {
    @Query("""
        SELECT e.* FROM episodes e 
        INNER JOIN playlist_items p ON e.id = p.episodeId 
        ORDER BY p.position ASC
    """)
    fun getPlaylistEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY position ASC")
    fun getPlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE episodeId = :episodeId")
    suspend fun removeFromPlaylist(episodeId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items")
    suspend fun getNextPosition(): Int

    @Query("DELETE FROM playlist_items")
    suspend fun clearPlaylist()

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_items WHERE episodeId = :episodeId)")
    suspend fun isInPlaylist(episodeId: Long): Boolean
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastTagCrossRef(crossRef: PodcastTagCrossRef)

    @Delete
    suspend fun deletePodcastTagCrossRef(crossRef: PodcastTagCrossRef)

    @Query("""
        SELECT t.* FROM tags t 
        INNER JOIN podcast_tag_cross_ref ptc ON t.id = ptc.tagId 
        WHERE ptc.podcastId = :podcastId
    """)
    fun getTagsForPodcast(podcastId: Long): Flow<List<TagEntity>>

    @Query("""
        SELECT p.* FROM podcasts p 
        INNER JOIN podcast_tag_cross_ref ptc ON p.id = ptc.podcastId 
        WHERE ptc.tagId = :tagId AND p.subscribed = 1
    """)
    fun getPodcastsForTag(tagId: Long): Flow<List<PodcastEntity>>
}
