package com.mvvtracker.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.mvvtracker.app.data.model.EventSeverity
import com.mvvtracker.app.data.model.EventSource
import com.mvvtracker.app.data.model.EventStatus

@Database(entities = [EventEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
}

class Converters {
    @TypeConverter fun toSeverity(v: String) = EventSeverity.valueOf(v)
    @TypeConverter fun fromSeverity(v: EventSeverity) = v.name

    @TypeConverter fun toSource(v: String) = EventSource.valueOf(v)
    @TypeConverter fun fromSource(v: EventSource) = v.name

    @TypeConverter fun toStatus(v: String) = EventStatus.valueOf(v)
    @TypeConverter fun fromStatus(v: EventStatus) = v.name
}
