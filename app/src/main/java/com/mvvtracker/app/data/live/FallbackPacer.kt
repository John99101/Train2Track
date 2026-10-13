package com.mvvtracker.app.data.live

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides when the app may ask our server and when it may ask transitous directly
 * instead (the fallback). The point: when our server is down, thousands of phones
 * must not all hit the community run transitous server every 20 s.
 *
 *  - Our server answered badly or not in time: it is left alone for
 *    [SERVER_BACKOFF_MS]; meanwhile the fallback is used.
 *  - The phone itself has no connection: no back off, the server is simply tried
 *    again at the next refresh (so the app recovers quickly after a tunnel).
 *  - transitous directly: at most one request per city every [DIRECT_MIN_INTERVAL_MS],
 *    whether it worked or not.
 *
 * Pure logic with the time passed in, so it can be unit tested. iOS mirrors it
 * (LiveTripsRepository.swift).
 */
class FallbackPacer {
    @Volatile private var serverRetryAtMs = 0L
    private val directRetryAtMs = ConcurrentHashMap<String, Long>()

    fun serverAllowed(nowMs: Long): Boolean = nowMs >= serverRetryAtMs

    fun onServerFailure(nowMs: Long, deviceOffline: Boolean) {
        if (!deviceOffline) serverRetryAtMs = nowMs + SERVER_BACKOFF_MS
    }

    fun directAllowed(cityId: String, nowMs: Long): Boolean = nowMs >= (directRetryAtMs[cityId] ?: 0L)

    /** Call right before a direct request for [cityId]. */
    fun onDirectRequest(cityId: String, nowMs: Long) {
        directRetryAtMs[cityId] = nowMs + DIRECT_MIN_INTERVAL_MS
    }

    companion object {
        const val SERVER_BACKOFF_MS = 2 * 60_000L
        const val DIRECT_MIN_INTERVAL_MS = 2 * 60_000L
    }
}
