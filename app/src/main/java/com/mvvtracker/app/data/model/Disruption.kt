package com.mvvtracker.app.data.model

/**
 * One live disruption/alert (Phase 6 / Option 2b). Produced server-side by the
 * `refreshDisruptions` Cloud Function from the gtfs.de GTFS-RT feed and stored in
 * the `disruptions` Firestore collection, so each device reads a few KB instead of
 * downloading the ~35 MB feed itself.
 */
data class Disruption(
    val id: String,
    val title: String,
    val description: String,
    val affected: List<String>,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val severe: Boolean
)
