package com.mvvtracker.app.data.repository

import com.mvvtracker.app.data.remote.ServerFunctions
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Inspection statistics from the `control_log` (see controlStats in functions/index.js). */
data class ControlStats(
    val days: Int,
    val total: Int,
    val trips: Int,
    val truncated: Boolean,
    val byLine: List<Pair<String, Int>>,
    val byStation: List<Pair<String, Int>>,
    val byWeekday: List<Int>,   // Monday first, Europe/Berlin
    val byHour: List<Int>       // 0..23, Europe/Berlin
)

/**
 * The admin console's server calls. Every one of these Cloud Functions checks the
 * caller's `admin` claim itself; hiding the console from riders is only the UX.
 */
@Singleton
class AdminBackend @Inject constructor(private val functions: ServerFunctions) {

    enum class Moderation(val action: String) { VERIFY("verify"), DISMISS("dismiss"), RESTORE("restore") }

    suspend fun moderateTrip(tripId: String, moderation: Moderation) {
        functions.call("moderateTrip", mapOf("tripId" to tripId, "action" to moderation.action))
    }

    suspend fun controlStats(days: Int = 30): ControlStats {
        val data = functions.call("controlStats", mapOf("days" to days), timeoutSeconds = 120)
            ?: error("empty response")
        return ControlStats(
            days = data.optInt("days"),
            total = data.optInt("total"),
            trips = data.optInt("trips"),
            truncated = data.optBoolean("truncated"),
            byLine = data.pairs("byLine"),
            byStation = data.pairs("byStation"),
            byWeekday = data.ints("byWeekday"),
            byHour = data.ints("byHour")
        )
    }

    /** Rebuilds the line routes of all cities (takes a few minutes on the server). */
    suspend fun rebuildRoutes() {
        functions.call("rebuildRoutes", timeoutSeconds = 9 * 60)
    }

    private fun JSONArray?.items(): List<Any?> =
        if (this == null) emptyList() else (0 until length()).map { opt(it) }

    private fun JSONObject.ints(key: String) = optJSONArray(key).items().map { (it as? Number)?.toInt() ?: 0 }

    private fun JSONObject.pairs(key: String) = optJSONArray(key).items().mapNotNull { row ->
        val r = row as? JSONArray ?: return@mapNotNull null
        val name = r.opt(0) as? String ?: return@mapNotNull null
        name to ((r.opt(1) as? Number)?.toInt() ?: 0)
    }
}
