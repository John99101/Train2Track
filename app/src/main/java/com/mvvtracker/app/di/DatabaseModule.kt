package com.mvvtracker.app.di

import android.content.Context
import androidx.room.Room
import com.mvvtracker.app.data.local.AppDatabase
import com.mvvtracker.app.data.local.EventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room database + DAO to Hilt. Without this, [EventDao]
 * (injected into EventRepository) cannot be constructed.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mvv_tracker.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
}
