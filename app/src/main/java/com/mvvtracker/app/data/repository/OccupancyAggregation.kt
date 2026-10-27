package com.mvvtracker.app.data.repository

/** One rider's answer to "how full is it?" on a journey, as read from Firestore. */
data class OccupancyVote(
    val tripId: String,
    /** 1 = many seats free ... 5 = very full. */
    val level: Int,
    val expiresAtMs: Long?,
    /** Over the spam limit (set by the server): does not count. */
    val throttled: Boolean,
    /** Written by this installation. */
    val own: Boolean
)

/**
 * What the app shows for one journey: [level] is the average of the current votes,
 * rounded to a level (null without votes). [ownLevel] is this installation's
 * current answer, if any.
 */
data class TripOccupancy(val level: Int?, val votes: Int, val ownLevel: Int?)

/**
 * Turns the occupancy votes into one level per journey: the average of the
 * current votes, rounded to the nearest level (halfway rounds up). So 3 and 4 show
 * 4, and a third rider with 3 brings it back to 3: every vote moves it. A vote
 * counts for 20 minutes (its expiry), because a train fills and empties at every
 * stop; votes over the spam limit never count. Port: iOS `OccupancyAggregation`
 * (MVVTrackerCore).
 */
object OccupancyAggregation {
    const val LEVELS = 5
    const val MIN_VOTES = 1

    fun aggregate(votes: List<OccupancyVote>, nowMs: Long): Map<String, TripOccupancy> =
        votes
            .filter { !it.throttled && it.level in 1..LEVELS && (it.expiresAtMs ?: 0L) > nowMs }
            .groupBy { it.tripId }
            .mapValues { (_, current) ->
                TripOccupancy(
                    level = if (current.size >= MIN_VOTES) average(current.map { it.level }) else null,
                    votes = current.size,
                    ownLevel = current.firstOrNull { it.own }?.level
                )
            }

    /** The average level, rounded to the nearest level; exactly halfway rounds up. */
    fun average(levels: List<Int>): Int {
        require(levels.isNotEmpty())
        val n = levels.size
        return (2 * levels.sum() + n) / (2 * n)
    }
}
