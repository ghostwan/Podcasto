package com.music.podcasto.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface ITunesApiService {
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 30,
    ): ITunesSearchResponse
}
