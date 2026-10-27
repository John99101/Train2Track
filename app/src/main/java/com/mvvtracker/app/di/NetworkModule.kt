package com.mvvtracker.app.di

import com.mvvtracker.app.BuildConfig
import com.mvvtracker.app.data.live.LiveApiService
import com.mvvtracker.app.data.live.LiveRoutesService
import com.mvvtracker.app.data.station.StationApiService
import com.mvvtracker.app.data.remote.LiveHostInterceptor
import com.mvvtracker.app.data.remote.TransportApiService
import com.mvvtracker.app.util.AppInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // Bodies are not logged in release builds to avoid leaking
            // any user-submitted incident text into device logs (GDPR data
            // minimization in logging).
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    // Transitous asks every app to say who it is: name, version and a contact
    // (https://transitous.org/api/). Only its requests carry this.
    @Provides
    @Singleton
    @Named("livemap")
    fun provideLiveMapRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.LIVE_MAP_API_BASE)
            .client(
                client.newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder().header("User-Agent", AppInfo.USER_AGENT).build()
                        )
                    }
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideTransportApiService(@Named("livemap") retrofit: Retrofit): TransportApiService =
        retrofit.create(TransportApiService::class.java)

    // Train2Track server (Firebase Hosting -> Cloud Function). Live snapshots must
    // fail fast so the app can fall back to transitous; the first routes request
    // for a city builds them on the server and may take up to a minute.
    private fun proxyRetrofit(client: OkHttpClient, timeoutSeconds: Long): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PROXY_API_BASE)
            .client(
                client.newBuilder()
                    // Live data through the CDN when the owner switched it on (LiveHost).
                    .addInterceptor(LiveHostInterceptor())
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .callTimeout(timeoutSeconds + 5, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideLiveApiService(client: OkHttpClient): LiveApiService =
        proxyRetrofit(client, timeoutSeconds = 15).create(LiveApiService::class.java)

    @Provides
    @Singleton
    fun provideLiveRoutesService(client: OkHttpClient): LiveRoutesService =
        proxyRetrofit(client, timeoutSeconds = 70).create(LiveRoutesService::class.java)

    @Provides
    @Singleton
    fun provideStationApiService(client: OkHttpClient): StationApiService =
        proxyRetrofit(client, timeoutSeconds = 15).create(StationApiService::class.java)
}
