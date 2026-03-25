package com.music.podcasto.di

import android.content.Context
import androidx.room.Room
import com.music.podcasto.data.local.*
import com.music.podcasto.data.remote.ITunesApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideITunesApiService(retrofit: Retrofit): ITunesApiService {
        return retrofit.create(ITunesApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PodcastoDatabase {
        return Room.databaseBuilder(
            context,
            PodcastoDatabase::class.java,
            "podcasto.db",
        ).build()
    }

    @Provides
    fun providePodcastDao(db: PodcastoDatabase): PodcastDao = db.podcastDao()

    @Provides
    fun provideEpisodeDao(db: PodcastoDatabase): EpisodeDao = db.episodeDao()

    @Provides
    fun providePlaylistDao(db: PodcastoDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideTagDao(db: PodcastoDatabase): TagDao = db.tagDao()
}
