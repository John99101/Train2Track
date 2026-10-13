package com.mvvtracker.app.data.model

import kotlinx.serialization.Serializable

/**
 * Where an event originated from. Used for attribution and for merging
 * duplicate reports that describe the same real-world incident.
 */
@Serializable
enum class EventSource {
    USER_REPORT,       // Submitted by an app user, unverified
    ADMIN_VERIFIED     // A user report promoted/verified by an app manager
}

@Serializable
enum class EventSeverity { INFO, MINOR_DELAY, MAJOR_DELAY, CANCELLATION, INCIDENT }

@Serializable
enum class EventStatus { ACTIVE, RESOLVED }

/**
 * What a rider is reporting about their own trip: whether they just boarded
 * ([ENTERED]) or got off ([EXITED]) a train. Replaces the old severity picker
 * on the report form.
 */
@Serializable
enum class MovementType(val label: String) {
    ENTERED("Entered"),
    EXITED("Exited")
}

/**
 * A single traffic/timing/disruption event on the Munich S-Bahn network.
 * User-submitted reports are normalized into this model before being shown on
 * the map and in the feed.
 */
@Serializable
data class SBahnEvent(
    val id: String,
    val lines: List<String>,           // e.g. ["S1", "S8"]
    val stationsAffected: List<String>,
    val title: String,
    val description: String,
    val severity: EventSeverity,
    val status: EventStatus,
    val source: EventSource,
    val latitude: Double?,
    val longitude: Double?,
    val startedAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long? = null,
    val reportedByUserId: String? = null,   // pseudonymous ID, never PII (see PrivacyPolicy)
    val verifiedByAdmin: Boolean = false,
    val tripId: String? = null              // the exact train run this report is about
)

/**
 * Payload a regular user submits to report a new incident, or to mark an
 * existing one as resolved. Kept minimal by design (data minimization,
 * GDPR Art. 5(1)(c)).
 */
@Serializable
data class UserEventReport(
    val lines: List<String>,
    val stationHint: String?,
    val description: String,
    val severity: EventSeverity,
    val latitude: Double?,
    val longitude: Double?,
    val tripId: String? = null   // the specific train run, if reported from the Feed
)
