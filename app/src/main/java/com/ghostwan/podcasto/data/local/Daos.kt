package com.ghostwan.podcasto.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts WHERE subscribed = 1 ORDER BY title ASC")
    fun getSubscribedPodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getPodcastById(id: Long): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE subscribed = 1")
    suspend fun getAllSubscribedPodcasts(): List<PodcastEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: PodcastEntity)

    @Query("UPDATE podcasts SET subscribed = :subscribed WHERE id = :id")
    suspend fun updateSubscription(id: Long, subscribed: Boolean)

    @Query("UPDATE podcasts SET hidden = :hidden WHERE id = :id")
    suspend fun updateHidden(id: Long, hidden: Boolean)

    @Query("SELECT id FROM podcasts WHERE hidden = 1")
    fun getHiddenPodcastIds(): Flow<List<Long>>

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcast(id: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDateTimestamp DESC")
    fun getEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND played = 0 ORDER BY pubDateTimestamp DESC")
    fun getUnplayedEpisodesForPodcast(podcastId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeById(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE podcastId IN (:podcastIds)")
    suspend fun getEpisodesForPodcasts(podcastIds: List<Long>): List<EpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("UPDATE episodes SET downloadPath = :path WHERE id = :id")
    suspend fun updateDownloadPath(id: Long, path: String?)

    @Query("SELECT * FROM episodes WHERE downloadPath IS NOT NULL")
    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("UPDATE episodes SET played = :played WHERE id = :id")
    suspend fun updatePlayed(id: Long, played: Boolean)

    @Query("UPDATE episodes SET playbackPosition = :position WHERE id = :id")
    suspend fun updatePlaybackPosition(id: Long, position: Long)

    @Query("UPDATE episodes SET played = 1, playbackPosition = 0 WHERE id = :id")
    suspend fun markAsPlayed(id: Long)

    @Query("""
        SELECT e.*, p.artworkUrl FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE p.subscribed = 1
        ORDER BY e.pubDateTimestamp DESC
        LIMIT 100
    """)
    fun getRecentEpisodesWithArtwork(): Flow<List<EpisodeWithArtwork>>

    @Query("""
        SELECT e.*, p.artworkUrl FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        INNER JOIN podcast_tag_cross_ref ptc ON p.id = ptc.podcastId
        WHERE p.subscribed = 1 AND ptc.tagId = :tagId
        ORDER BY e.pubDateTimestamp DESC
        LIMIT 100
    """)
    fun getRecentEpisodesWithArtworkForTag(tagId: Long): Flow<List<EpisodeWithArtwork>>

    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        WHERE p.subscribed = 1 AND p.hidden = 0 AND e.played = 0
          AND e.pubDateTimestamp = (
              SELECT MAX(e2.pubDateTimestamp) FROM episodes e2
              WHERE e2.podcastId = e.podcastId AND e2.played = 0
          )
        ORDER BY e.pubDateTimestamp DESC
    """)
    suspend fun getLatestEpisodesFromSubscriptions(): List<EpisodeEntity>

    @Query("""
        SELECT e.* FROM episodes e
        INNER JOIN podcasts p ON e.podcastId = p.id
        INNER JOIN podcast_tag_cross_ref ptc ON p.id = ptc.podcastId
        WHERE ptc.tagId = :tagId AND p.subscribed = 1 AND p.hidden = 0 AND e.played = 0
          AND e.pubDateTimestamp = (
              SELECT MAX(e2.pubDateTimestamp) FROM episodes e2
              WHERE e2.podcastId = e.podcastId AND e2.played = 0
          )
        ORDER BY e.pubDateTimestamp DESC
    """)
    suspend fun getLatestEpisodesForTag(tagId: Long): List<EpisodeEntity>

    @Query("""
        SELECT podcastId, MAX(pubDateTimestamp) AS latestTimestamp
        FROM episodes
        GROUP BY podcastId
    """)
    fun getLatestEpisodeTimestampPerPodcast(): Flow<List<PodcastLatestTimestamp>>
}

@Dao
interface PlaylistDao {
    @Query("""
        SELECT e.* FROM episodes e 
        INNER JOIN playlist_items p ON e.id = p.episodeId 
        ORDER BY p.position ASC
    """)
    fun getPlaylistEpisodes(): Flow<List<EpisodeEntity>>

    @Query("""
        SELECT e.* FROM episodes e 
        INNER JOIN playlist_items p ON e.id = p.episodeId 
        ORDER BY p.position ASC
    """)
    suspend fun getPlaylistEpisodesList(): List<EpisodeEntity>

    @Query("""
        SELECT e.*, pod.artworkUrl FROM episodes e 
        INNER JOIN playlist_items p ON e.id = p.episodeId 
        INNER JOIN podcasts pod ON e.podcastId = pod.id
        ORDER BY p.position ASC
    """)
    fun getPlaylistEpisodesWithArtwork(): Flow<List<EpisodeWithArtwork>>

    @Query("""
        SELECT e.*, pod.artworkUrl FROM episodes e 
        INNER JOIN playlist_items p ON e.id = p.episodeId 
        INNER JOIN podcasts pod ON e.podcastId = pod.id
        ORDER BY p.position ASC
    """)
    suspend fun getPlaylistEpisodesWithArtworkList(): List<EpisodeWithArtwork>

    @Query("SELECT * FROM playlist_items ORDER BY position ASC")
    fun getPlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItem(item: PlaylistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItems(items: List<PlaylistItemEntity>)

    @Query("SELECT * FROM playlist_items ORDER BY position ASC")
    suspend fun getAllPlaylistItems(): List<PlaylistItemEntity>

    @Query("DELETE FROM playlist_items WHERE episodeId = :episodeId")
    suspend fun removeFromPlaylist(episodeId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items")
    suspend fun getNextPosition(): Int

    @Query("DELETE FROM playlist_items")
    suspend fun clearPlaylist()

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_items WHERE episodeId = :episodeId)")
    suspend fun isInPlaylist(episodeId: Long): Boolean

    @Query("UPDATE playlist_items SET position = :position WHERE episodeId = :episodeId")
    suspend fun updatePosition(episodeId: Long, position: Int)

    @Query("UPDATE playlist_items SET position = position + 1")
    suspend fun shiftAllPositions()
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun getAllTagsList(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Delete
    suspend fun deleteTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastTagCrossRef(crossRef: PodcastTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastTagCrossRefs(crossRefs: List<PodcastTagCrossRef>)

    @Delete
    suspend fun deletePodcastTagCrossRef(crossRef: PodcastTagCrossRef)

    @Query("SELECT * FROM podcast_tag_cross_ref WHERE podcastId IN (:podcastIds)")
    suspend fun getCrossRefsForPodcasts(podcastIds: List<Long>): List<PodcastTagCrossRef>

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

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE episodeId = :episodeId ORDER BY positionMs ASC")
    fun getBookmarksForEpisode(episodeId: Long): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks")
    suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<BookmarkEntity>)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)
}

@Dao
interface HistoryDao {
    @Query("""
        SELECT h.*, e.title AS episodeTitle, p.title AS podcastTitle, p.artworkUrl
        FROM listening_history h
        INNER JOIN episodes e ON h.episodeId = e.id
        INNER JOIN podcasts p ON h.podcastId = p.id
        ORDER BY h.listenedAt DESC
    """)
    fun getHistoryWithDetails(): Flow<List<HistoryWithDetails>>

    @Insert
    suspend fun insertHistoryEntry(entry: HistoryEntity)

    @Query("SELECT * FROM listening_history")
    suspend fun getAllHistory(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntries(entries: List<HistoryEntity>)

    @Query("DELETE FROM listening_history")
    suspend fun clearHistory()
}
