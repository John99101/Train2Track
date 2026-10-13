package com.mvvtracker.app.data.repository

import com.mvvtracker.app.data.local.EventDao
import com.mvvtracker.app.data.local.EventEntity
import com.mvvtracker.app.data.model.EventSource
import com.mvvtracker.app.data.model.EventStatus
import com.mvvtracker.app.data.model.Disruption
import com.mvvtracker.app.data.model.SBahnEvent
import com.mvvtracker.app.data.model.UserEventReport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for S-Bahn events. Persists user reports locally (Room)
 * so the map/feed screens observe one consistent stream; nationwide disruptions
 * come from the `disruptions` collection (see [observeDisruptions]).
 */
@Singleton
class EventRepository @Inject constructor(
    private val eventDao: EventDao,
    private val userReportBackend: UserReportBackend
) {

    fun observeActiveEvents(): Flow<List<SBahnEvent>> =
        eventDao.observeActiveEvents().map { list -> list.map { it.toDomain() } }

    /**
     * Live nationwide disruptions. These are fetched server-side by the
     * `refreshDisruptions` Cloud Function (one 35 MB gtfs.de pull for everyone) and
     * written to the `disruptions` collection; each device just reads a few KB.
     */
    fun observeDisruptions(): Flow<List<Disruption>> = userReportBackend.observeDisruptions()

    /**
     * Submits a user-reported incident. Stored with the reporter's
     * pseudonymous ID only (never name/email/device ID) - see
     * privacy/ConsentManager.pseudonymousId. [city] is the report city
     * of the train (see reportCityAt in GermanCity.kt).
     */
    suspend fun submitUserReport(report: UserEventReport, pseudonymousUserId: String, city: String): SBahnEvent {
        val event = SBahnEvent(
            id = "user-${UUID.randomUUID()}",
            lines = report.lines,
            stationsAffected = listOfNotNull(report.stationHint),
            title = "${report.lines.joinToString()}: ${report.description}",
            description = report.description,
            severity = report.severity,
            status = EventStatus.ACTIVE,
            source = EventSource.USER_REPORT,
            latitude = report.latitude,
            longitude = report.longitude,
            startedAtEpochMillis = System.currentTimeMillis(),
            reportedByUserId = pseudonymousUserId,
            tripId = report.tripId
        )
        // One active report per device per train: retire any earlier one first.
        report.tripId?.let { eventDao.resolvePriorUserReportsForTrip(it, System.currentTimeMillis()) }
        eventDao.upsertAll(listOf(event.toEntity(System.currentTimeMillis())))
        // Also push to backend so other users' apps receive it (and so an
        // app manager can verify/promote or dismiss it). If that fails, the
        // report exists nowhere but on this phone: take its "!" back, so the
        // phone does not show a report nobody else can see.
        try {
            userReportBackend.submitReport(event, city)
        } catch (e: Exception) {
            report.tripId?.let { tripId ->
                withContext(NonCancellable) {
                    eventDao.resolvePriorUserReportsForTrip(tripId, System.currentTimeMillis())
                }
            }
            throw e
        }
        return event
    }

    /**
     * Crowd-sourced delay vote (Phase 6 / Option 2): the rider confirms the train
     * is delayed or says it's on time. Stored in a separate backend collection —
     * never touches the inspector-report flow.
     */
    suspend fun submitDelayVote(tripId: String, confirm: Boolean, city: String) =
        userReportBackend.submitDelayVote(
            tripId,
            if (confirm) UserReportBackend.DELAY_CONFIRM else UserReportBackend.DELAY_ON_TIME,
            city
        )

    /** Live per-trip delay tally: tripId -> (confirm, onTime). */
    fun observeDelayVotes() = userReportBackend.observeDelayVotes()

    /** "Exited": ends this train's report locally + on the backend. */
    suspend fun endUserReport(tripId: String, city: String) {
        val now = System.currentTimeMillis()
        eventDao.resolvePriorUserReportsForTrip(tripId, now)
        userReportBackend.endReport(tripId, city)
    }

    /**
     * Run when the app starts: ends this device's reports older than
     * [REPORT_TTL_MILLIS] so alerts expire, and deletes the ones older than
     * [EventEntity.RETENTION_DAYS] from the phone (as the privacy policy says).
     */
    suspend fun expireStaleReports() {
        val now = System.currentTimeMillis()
        eventDao.resolveStaleUserReports(cutoff = now - REPORT_TTL_MILLIS, now = now)
        eventDao.purgeOlderThan(now - EventEntity.RETENTION_DAYS * DAY_MILLIS)
    }

    companion object {
        /** How long a user's train report stays "live" before auto-expiring. */
        const val REPORT_TTL_MILLIS = 90L * 60 * 1000
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

}

private fun EventEntity.toDomain() = SBahnEvent(
    id = id,
    lines = linesCsv.split(",").filter { it.isNotBlank() },
    stationsAffected = stationsCsv.split(",").filter { it.isNotBlank() },
    title = title,
    description = description,
    severity = severity,
    status = status,
    source = source,
    latitude = latitude,
    longitude = longitude,
    startedAtEpochMillis = startedAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
    reportedByUserId = reportedByUserId,
    verifiedByAdmin = verifiedByAdmin,
    tripId = tripId
)

private fun SBahnEvent.toEntity(cachedAt: Long) = EventEntity(
    id = id,
    linesCsv = lines.joinToString(","),
    stationsCsv = stationsAffected.joinToString(","),
    title = title,
    description = description,
    severity = severity,
    status = status,
    source = source,
    latitude = latitude,
    longitude = longitude,
    startedAtEpochMillis = startedAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
    reportedByUserId = reportedByUserId,
    verifiedByAdmin = verifiedByAdmin,
    cachedAtEpochMillis = cachedAt,
    tripId = tripId
)
