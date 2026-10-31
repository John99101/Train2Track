package com.mvvtracker.app.data.station

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * The Train2Track server's station API (functions/lib/stations.js). Nothing is
 * loaded in advance: a search when the rider types, one station's departures when
 * the rider opens it, the station dots once the rider switches them on.
 */
interface StationApiService {
    /** Stations of the city whose name matches [text] (2 to 40 characters). */
    @GET("api/stations/{city}/{text}")
    suspend fun search(@Path("city") cityId: String, @Path("text") text: String): StationsDto

    /** The next departures at [stopId]. */
    @GET("api/station/{city}/{stop}")
    suspend fun station(@Path("city") cityId: String, @Path("stop") stopId: String): StationDto

    /**
     * The city's stations for the map's "Stations" button. Only asked once the
     * rider switched the button on, then kept on the phone for days (see [MapStationStore]).
     */
    @GET("api/stops/{city}")
    suspend fun mapStations(@Path("city") cityId: String, @Header("If-None-Match") etag: String? = null): Response<MapStationsDto>
}

/**
 * stations: [name, lat, lon, bits, id]; bits = the map buttons of the lines that
 * stop there (see [MapGroups]). complete = false while the server is still
 * collecting the stations of some lines: then the phone asks again sooner.
 */
@Serializable
data class MapStationsDto(
    val v: Int = 1,
    val city: String = "",
    val built: Long = 0,
    val complete: Boolean = true,
    val stations: List<JsonArray> = emptyList()
)

/** The map's filter buttons as bits, the same as the server's MAP_GROUP_BITS. */
object MapGroups {
    const val SBAHN = 1
    const val UBAHN = 2
    const val TRAM = 4
    const val BUS = 8
    const val RAIL = 16

    /** The modes the station page shows symbols for (the timetable's names, as in [StationRef.modes]). */
    fun modes(bits: Int): List<String> = buildList {
        if (bits and SBAHN != 0) add("SUBURBAN")
        if (bits and UBAHN != 0) add("SUBWAY")
        if (bits and TRAM != 0) add("TRAM")
        if (bits and BUS != 0) add("BUS")
        if (bits and RAIL != 0) add("REGIONAL_RAIL")
    }
}

/** A station dot on the map. */
data class MapStation(val id: String, val name: String, val lat: Double, val lon: Double, val bits: Int)

private fun JsonArray.dbl(i: Int): Double? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

fun MapStationsDto.toMapStations(): List<MapStation> = stations.mapNotNull { row ->
    val name = row.str(0)
    val lat = row.dbl(1) ?: return@mapNotNull null
    val lon = row.dbl(2) ?: return@mapNotNull null
    val bits = row.int(3)
    val id = row.str(4)
    if (name.isBlank() || id.isBlank() || bits == 0) null else MapStation(id, name, lat, lon, bits)
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
