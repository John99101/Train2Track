package com.mvvtracker.app.data.repository

/** One document of the shared `reports` collection, as far as the rules need it. */
data class ReportDoc(
    /**
     * Tells the riders on one train apart: a different value per rider. It is the
     * hidden name of the entry (see [ReportIds]), not the rider's id.
     */
    val rider: String?,
    val tripId: String,
    val state: String?,          // "entered" | "exited" (missing = legacy entry)
    val line: String?,
    val station: String?,
    val createdAtMs: Long?,
    val expiresAtMs: Long?,
    val throttled: Boolean = false,   // over the spam limit (set by the server)
    val dismissed: Boolean = false,   // hidden by an admin (set by the server)
    val verified: Boolean = false,    // checked by an admin (set by the server)
    /** This device's own entry. */
    val own: Boolean = false
)

/** The report state of one train, after the rules below. */
data class TripReports(
    val tripId: String,
    val line: String?,
    val station: String?,
    /** Riders whose "on board" report counts. */
    val onBoard: Int,
    /** "Has left" votes newer than the latest "on board" report. */
    val exits: Int,
    /** What the "!" shows: [onBoard], or 0 once the control is confirmed gone. */
    val flagged: Int,
    val verified: Boolean,
    val dismissed: Int,
    val throttled: Int,
    val latestMs: Long
)

/**
 * The rules that turn the raw reports of a train into its "!" state. iOS must
 * implement exactly the same rules so both apps agree.
 *
 *  - Expired reports, reports over the spam limit and reports an admin dismissed
 *    do not count.
 *  - A rider who reported "on board" and then "has left" flips their own report,
 *    so their entry simply stops counting.
 *  - "Has left" from OTHER riders clears the train only once [EXIT_QUORUM]
 *    different riders said so after the latest "on board" report. Before, a
 *    single tap from anyone cleared it for everyone, which made it easy to abuse.
 *  - The rider who voted "has left" no longer sees the "!" themselves right away.
 */
object ReportAggregation {

    const val STATE_EXITED = "exited"
    const val EXIT_QUORUM = 2

    fun aggregate(docs: List<ReportDoc>, nowMs: Long): Map<String, TripReports> =
        docs.groupBy { it.tripId }.mapValues { (tripId, all) -> forTrip(tripId, all, nowMs) }

    private fun forTrip(tripId: String, all: List<ReportDoc>, nowMs: Long): TripReports {
        val live = all.filter { it.expiresAtMs == null || it.expiresAtMs > nowMs }
        val valid = live.filter { !it.dismissed && !it.throttled }
        val entries = valid.filter { it.state != STATE_EXITED }
        val exits = valid.filter { it.state == STATE_EXITED }
        val latestEntry = entries.maxOfOrNull { it.createdAtMs ?: nowMs }
        val freshExits = if (latestEntry == null) emptyList()
                         else exits.filter { (it.createdAtMs ?: nowMs) > latestEntry }
        val selfLeft = freshExits.any { it.own }
        val flagged = when {
            entries.isEmpty() -> 0
            freshExits.map { it.rider }.distinct().size >= EXIT_QUORUM -> 0
            selfLeft -> 0
            else -> entries.size
        }
        val newest = (entries.ifEmpty { live }).maxByOrNull { it.createdAtMs ?: 0L }
        return TripReports(
            tripId = tripId,
            line = newest?.line?.takeIf { it.isNotBlank() },
            station = newest?.station?.takeIf { it.isNotBlank() },
            onBoard = entries.size,
            exits = freshExits.size,
            flagged = flagged,
            verified = valid.any { it.verified },
            dismissed = live.count { it.dismissed },
            throttled = live.count { it.throttled && !it.dismissed },
            latestMs = live.maxOfOrNull { it.createdAtMs ?: 0L } ?: 0L
        )
    }
}
