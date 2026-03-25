package com.music.podcasto.data.remote

import com.google.gson.annotations.SerializedName

data class ITunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int,
    @SerializedName("results") val results: List<ITunesPodcast>,
)

data class ITunesPodcast(
    @SerializedName("collectionId") val collectionId: Long,
    @SerializedName("collectionName") val collectionName: String,
    @SerializedName("artistName") val artistName: String,
    @SerializedName("artworkUrl600") val artworkUrl600: String?,
    @SerializedName("artworkUrl100") val artworkUrl100: String?,
    @SerializedName("feedUrl") val feedUrl: String?,
    @SerializedName("collectionViewUrl") val collectionViewUrl: String?,
)
