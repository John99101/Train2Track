package com.mvvtracker.app.data.station

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The Train2Track server's station API (functions/lib/stations.js). Nothing is
 * loaded in advance and nothing goes on the map: a search when the rider types,
 * one station's departures when the rider opens it.
 */
interface StationApiService {
    /** Stations of the city whose name matches [text] (2 to 40 characters). */
    @GET("api/stations/{city}/{text}")
    suspend fun search(@Path("city") cityId: String, @Path("text") text: String): StationsDto

    /** The next departures at [stopId]. */
    @GET("api/station/{city}/{stop}")
    suspend fun station(@Path("city") cityId: String, @Path("stop") stopId: String): StationDto
}

/** stations: [id, name, modes] */
@Serializable
data class StationsDto(val v: Int = 1, val city: String = "", val stations: List<JsonArray> = emptyList())

/**
 * deps: [label, mode, colour, destination, planned, expected, platform, flags, tripId]
 * with times in seconds relative to [t]; flags 1 = live data, 2 = cancelled.
 */
@Serializable
data class StationDto(
    val v: Int = 1,
    val city: String = "",
    val t: Long = 0,
    val name: String = "",
    val deps: List<JsonArray> = emptyList()
)

/** A station the rider found or opened. [modes] as the timetable names them (SUBURBAN, SUBWAY, ...). */
data class StationRef(val id: String, val name: String, val cityId: String, val modes: List<String> = emptyList())

data class Departure(
    val label: String,
    /** The same mode names as the live data (METRO = S-Bahn, SUBWAY = U-Bahn, ...). */
    val mode: String,
    val colorHex: String,
    val destination: String,
    val plannedAtMs: Long,
    val expectedAtMs: Long,
    val platform: String,
    val realtime: Boolean,
    val cancelled: Boolean,
    val tripId: String
) {
    /** Minutes late (0 when on time or without live data). */
    val delayMinutes: Int get() = (((expectedAtMs - plannedAtMs) / 60_000L).toInt()).coerceAtLeast(0)
}

/** One station's board. */
data class StationBoard(val name: String, val departures: List<Departure>, val loadedAtMs: Long)

private fun JsonArray.str(i: Int): String = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
private fun JsonArray.long(i: Int): Long? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
private fun JsonArray.int(i: Int): Int = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull ?: 0

fun StationsDto.toRefs(cityId: String): List<StationRef> = stations.mapNotNull { row ->
    val id = row.str(0)
    val name = row.str(1)
    if (id.isBlank() || name.isBlank()) null
    else StationRef(id, name, cityId, row.str(2).split(",").filter { it.isNotBlank() })
}

fun StationDto.toBoard(nowMs: Long = System.currentTimeMillis()): StationBoard {
    val baseMs = t * 1000L
    val departures = deps.mapNotNull { row ->
        val expected = row.long(5) ?: return@mapNotNull null
        val planned = row.long(4) ?: expected
        val flags = row.int(7)
        Departure(
            label = row.str(0).ifBlank { "?" },
            mode = row.str(1),
            colorHex = row.str(2),
            destination = row.str(3),
            plannedAtMs = baseMs + planned * 1000L,
            expectedAtMs = baseMs + expected * 1000L,
            platform = row.str(6),
            realtime = flags and 1 != 0,
            cancelled = flags and 2 != 0,
            tripId = row.str(8)
        )
    }
    // Each journey once (the list keys rows by it).
    return StationBoard(name, departures.distinctBy { "${it.tripId}|${it.plannedAtMs}|${it.label}" }, nowMs)
}

/** The search text as the server takes it: trimmed, single spaces, lower case; null when too short or long. */
fun normalizeStationQuery(text: String): String? {
    val q = text.trim().replace(Regex("\\s+"), " ").lowercase()
    return q.takeIf { it.length in 2..40 }
}
