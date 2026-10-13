package com.mvvtracker.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.mvvtracker.app.data.model.EventSeverity
import com.mvvtracker.app.data.model.EventSource
import com.mvvtracker.app.data.model.EventStatus
import kotlinx.coroutines.flow.Flow

/**
 * This device's own reports, kept locally so the "!" appears at once. No
 * personal data beyond a pseudonymous reporter ID that never leaves the phone.
 * Rows are deleted after [RETENTION_DAYS] via [EventDao.purgeOlderThan], run at
 * app start (GDPR Art. 5(1)(e) storage limitation).
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val linesCsv: String,
    val stationsCsv: String,
    val title: String,
    val description: String,
    val severity: EventSeverity,
    val status: EventStatus,
    val source: EventSource,
    val latitude: Double?,
    val longitude: Double?,
    val startedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long?,
    val reportedByUserId: String?,
    val verifiedByAdmin: Boolean,
    val cachedAtEpochMillis: Long,
    val tripId: String? = null
) {
    companion object {
        const val RETENTION_DAYS = 30
    }
}

@Dao
interface EventDao {

    @Upsert
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE status = 'ACTIVE' ORDER BY startedAtEpochMillis DESC")
    fun observeActiveEvents(): Flow<List<EventEntity>>

    /** Auto-expire user reports older than the cutoff so alerts don't linger forever. */
    @Query(
        "UPDATE events SET status = 'RESOLVED', resolvedAtEpochMillis = :now " +
            "WHERE status = 'ACTIVE' AND source = 'USER_REPORT' AND startedAtEpochMillis < :cutoff"
    )
    suspend fun resolveStaleUserReports(cutoff: Long, now: Long)

    /** One active report per device per train: retire this device's earlier report for the trip. */
    @Query(
        "UPDATE events SET status = 'RESOLVED', resolvedAtEpochMillis = :now " +
            "WHERE status = 'ACTIVE' AND source = 'USER_REPORT' AND tripId = :tripId"
    )
    suspend fun resolvePriorUserReportsForTrip(tripId: String, now: Long)

    @Query("DELETE FROM events WHERE cachedAtEpochMillis < :cutoffEpochMillis")
    suspend fun purgeOlderThan(cutoffEpochMillis: Long)

    /** "Delete my data": drops this device's locally kept reports. */
    @Query("DELETE FROM events")
    suspend fun clearAll()
}
