package com.mvvtracker.app.data.live

/**
 * How a vehicle moves between its stops on the map. The live data has no GPS
 * positions: only each hop's departure and arrival (whole minutes, with the live
 * delay), and the apps place the vehicle along the hop's track by time.
 *
 *  - Standing: between arriving at a stop and leaving it again a vehicle has no hop.
 *    It used to vanish from the map and the Feed for up to two minutes at every
 *    stop. [withStandingTimes] gives every hop the moment from which its vehicle
 *    stands at the stop it leaves from ([LiveSegment.standFromMs]).
 *  - Speed: a vehicle speeds up after a stop and brakes before the next one, it does
 *    not run at one speed from stop to stop. [distanceFraction] turns the share of a
 *    hop's time into the share of its way with a slow start, a steady middle and a
 *    slow arrival.
 *
 * The same rules on iOS: `LiveMotion` in MVVTrackerCore.
 */
object LiveMotion {
    /** At most this long standing at a stop (a long turn at the end of a line is cut). */
    const val MAX_STAND_MS = 5 * 60_000L

    /**
     * A vehicle whose earlier hop is not in the data (it starts its journey there,
     * or it arrived before the data's time window) is shown at its stop this long
     * before it leaves.
     */
    const val FIRST_STAND_MS = 60_000L

    /**
     * The hops with [LiveSegment.standFromMs]: from the arrival of the vehicle's
     * hop before (at the same stop), else [FIRST_STAND_MS] before the departure.
     * Hops without a journey id keep their departure (no way to know their vehicle's
     * hop before, and two badges for one vehicle would be worse than a gap).
     */
    /**
     * When a vehicle started standing at a stop, kept across downloads. The data only
     * reaches about a minute back, so at a long stop the hop that brought the vehicle
     * drops out while it still stands there; without this its standing time would
     * jump to [FIRST_STAND_MS] before it leaves and it would vanish from the map.
     * Keyed by journey and stop; a time is only taken over while the departure moved
     * less than [MAX_STAND_MS] (a delay, not a second visit of a loop line).
     */
    class StandMemory {
        private class Seen(val standFromMs: Long, val departureMs: Long)

        private val seen = HashMap<String, Seen>()

        @Synchronized
        fun keep(key: String, standFromMs: Long, departureMs: Long): Long {
            val earlier = seen[key]
            val kept = if (earlier != null && earlier.standFromMs < standFromMs &&
                kotlin.math.abs(earlier.departureMs - departureMs) <= MAX_STAND_MS
            ) {
                maxOf(earlier.standFromMs, departureMs - MAX_STAND_MS)
            } else {
                standFromMs
            }
            seen[key] = Seen(kept, departureMs)
            return kept
        }

        /** Forgets vehicles that left long ago. */
        @Synchronized
        fun prune(nowMs: Long) {
            seen.values.removeAll { it.departureMs < nowMs - MAX_STAND_MS }
        }
    }

    fun withStandingTimes(
        segments: List<LiveSegment>,
        memory: StandMemory? = null,
        nowMs: Long = System.currentTimeMillis()
    ): List<LiveSegment> {
        val stand = LongArray(segments.size) { i ->
            val s = segments[i]
            if (s.tripId == null) s.departureMs else s.departureMs - FIRST_STAND_MS
        }
        val byTrip = HashMap<String, MutableList<Int>>()
        segments.forEachIndexed { i, s -> s.tripId?.let { byTrip.getOrPut(it) { ArrayList(4) }.add(i) } }
        for (indices in byTrip.values) {
            if (indices.size < 2) continue
            indices.sortBy { segments[it].departureMs }
            for (k in 1 until indices.size) {
                val prev = segments[indices[k - 1]]
                val i = indices[k]
                val s = segments[i]
                stand[i] = if (prev.arrivalMs <= s.departureMs && sameStop(prev, s)) {
                    maxOf(prev.arrivalMs, s.departureMs - MAX_STAND_MS)
                } else {
                    // The hops overlap (the same hop twice) or do not meet: no standing.
                    s.departureMs
                }
            }
        }
        if (memory != null) {
            memory.prune(nowMs)
            segments.forEachIndexed { i, s ->
                val trip = s.tripId ?: return@forEachIndexed
                if (stand[i] < s.departureMs) stand[i] = memory.keep("$trip|${stopKey(s)}", stand[i], s.departureMs)
            }
        }
        return segments.mapIndexed { i, s -> if (stand[i] == s.standFromMs) s else s.copy(standFromMs = stand[i]) }
    }

    /** The stop a hop leaves from: its name, else its place. */
    private fun stopKey(s: LiveSegment): String = s.fromName ?: "${s.fromLat},${s.fromLon}"

    /** Whether hop [b] starts where hop [a] ends. */
    private fun sameStop(a: LiveSegment, b: LiveSegment): Boolean {
        if (a.toName != null && a.toName == b.fromName) return true
        if (a.toLat.isNaN() || b.fromLat.isNaN()) return false
        return kotlin.math.abs(a.toLat - b.fromLat) < 0.002 && kotlin.math.abs(a.toLon - b.fromLon) < 0.003
    }

    /** Seconds a vehicle of [mode] takes to reach its speed after a stop (and to brake before one). */
    private fun rampSeconds(mode: String): Double = when (mode) {
        "BUS" -> 12.0
        "TRAM" -> 15.0
        "SUBWAY" -> 20.0
        else -> 25.0
    }

    /**
     * The share of its way a vehicle of [mode] has covered [elapsedMs] into a hop of
     * [durationMs]: speeding up, steady, braking (the speeding up and the braking take
     * at most a third of the hop each). 0 before it leaves, 1 when it arrives.
     */
    fun distanceFraction(elapsedMs: Long, durationMs: Long, mode: String): Double {
        if (durationMs <= 0) return 1.0
        val total = durationMs / 1000.0
        val t = (elapsedMs / 1000.0).coerceIn(0.0, total)
        val ramp = minOf(rampSeconds(mode), total / 3.0)
        val speed = 1.0 / (total - ramp)   // the steady speed, in shares of the way per second
        return when {
            t < ramp -> speed * t * t / (2 * ramp)
            t > total - ramp -> 1.0 - speed * (total - t) * (total - t) / (2 * ramp)
            else -> speed * (t - ramp / 2)
        }
    }
}
