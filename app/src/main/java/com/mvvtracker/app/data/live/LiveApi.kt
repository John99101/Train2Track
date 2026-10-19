package com.mvvtracker.app.data.live

import com.mvvtracker.app.data.remote.GeoPolyline
import com.mvvtracker.app.data.remote.MapTripSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import java.time.Instant

/**
 * The Train2Track server's live data API (Firebase Hosting -> Cloud Function
 * `api`, see functions/lib/liveApi.js). One compact snapshot per city replaces the
 * ~1.2 MB raw transitous response every device used to download every 30 s.
 */
interface LiveApiService {
    /** The whole city (a server not updated yet knows only this one). */
    @GET("api/live/{city}")
    suspend fun live(@Path("city") cityId: String): LiveSnapshotDto

    /**
     * One [LiveGroup] of the city. With the ETag of the data the app already has in
     * [etag], the server answers 304 (no body) as long as nothing changed.
     */
    @GET("api/live/{city}/{group}")
    suspend fun liveGroup(
        @Path("city") cityId: String,
        @Path("group") group: String,
        @Header("If-None-Match") etag: String?
    ): Response<LiveSnapshotDto>

    @GET("api/shapes/{city}")
    suspend fun shapes(@Path("city") cityId: String): ShapesDto
}

/** Separate service with a long timeout: the first request for a city builds its routes. */
interface LiveRoutesService {
    @GET("api/routes/{city}")
    suspend fun routes(@Path("city") cityId: String): RoutesDto

    /**
     * The route of one line the city's set does not have yet (a long distance
     * train, a night line). The server builds it once from the journey [tripId],
     * which must be running on that line, and keeps it for everybody. 404 when
     * there is none.
     */
    @GET("api/route/{city}/{mode}/{label}/{trip}")
    suspend fun route(
        @Path("city") cityId: String,
        @Path("mode") mode: String,
        @Path("label") label: String,
        @Path("trip") tripId: String
    ): RouteDto
}

/**
 * Compact live snapshot (format v1). Segment rows are
 * [lineIdx, tripId, fromStopIdx, toStopIdx, dep, arr, delaySec|null, realtime 0/1, shapeKey]
 * with times in seconds relative to [t].
 */
@Serializable
data class LiveSnapshotDto(
    val v: Int = 1,
    val city: String = "",
    val t: Long = 0,
    val stale: Boolean = false,
    val stops: List<JsonArray> = emptyList(),
    val lines: List<JsonArray> = emptyList(),
    val segs: List<JsonArray> = emptyList()
)

@Serializable
data class ShapesDto(
    val v: Int = 1,
    val city: String = "",
    val shapes: Map<String, String> = emptyMap()
)

/** Rows are [label, mode, colour hex, encoded polyline (precision 5)]. */
@Serializable
data class RoutesDto(
    val v: Int = 1,
    val city: String = "",
    val built: Long = 0,
    val routes: List<List<String>> = emptyList()
)

/** One row [label, mode, colour hex, encoded polyline (precision 5)]. */
@Serializable
data class RouteDto(
    val v: Int = 1,
    val city: String = "",
    val route: List<String> = emptyList()
)

/**
 * One vehicle on one hop: from [fromName] to [toName] between [departureMs] and
 * [arrivalMs]. Its geometry lives in [ShapeStore] under [shapeKey]; when that is
 * not known yet the vehicle moves on a straight line between the two stops.
 */
data class LiveSegment(
    val tripId: String?,
    val label: String,
    val mode: String,
    val colorHex: String?,
    val fromName: String?,
    val toName: String?,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val departureMs: Long,
    val arrivalMs: Long,
    val delayMinutes: Int?,
    val isRealtime: Boolean,
    val shapeKey: String
) {
    val hasStopCoordinates: Boolean
        get() = !fromLat.isNaN() && !fromLon.isNaN() && !toLat.isNaN() && !toLon.isNaN()
}

private fun JsonArray.str(i: Int): String? =
    (getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonArray.int(i: Int): Int? = (getOrNull(i) as? JsonPrimitive)?.intOrNull

private fun JsonArray.dbl(i: Int): Double = (getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: Double.NaN

/** Expands the compact snapshot into segments (absolute times, resolved tables). */
fun LiveSnapshotDto.toSegments(): List<LiveSegment> {
    val baseMs = t * 1000L
    val stopNames = stops.map { it.str(0) }
    val stopLats = stops.map { it.dbl(1) }
    val stopLons = stops.map { it.dbl(2) }
    return segs.mapNotNull { s ->
        val line = lines.getOrNull(s.int(0) ?: -1) ?: return@mapNotNull null
        val from = s.int(2) ?: -1
        val to = s.int(3) ?: -1
        val dep = s.int(4) ?: return@mapNotNull null
        val arr = s.int(5) ?: return@mapNotNull null
        val realtime = s.int(7) == 1
        val delaySec = s.int(6)
        LiveSegment(
            tripId = s.str(1)?.takeIf { it.isNotEmpty() },
            label = line.str(0)?.takeIf { it.isNotBlank() } ?: "?",
            mode = line.str(1) ?: return@mapNotNull null,
            colorHex = line.str(2)?.takeIf { it.isNotBlank() },
            fromName = stopNames.getOrNull(from),
            toName = stopNames.getOrNull(to),
            fromLat = stopLats.getOrElse(from) { Double.NaN },
            fromLon = stopLons.getOrElse(from) { Double.NaN },
            toLat = stopLats.getOrElse(to) { Double.NaN },
            toLon = stopLons.getOrElse(to) { Double.NaN },
            departureMs = baseMs + dep * 1000L,
            arrivalMs = baseMs + arr * 1000L,
            delayMinutes = if (realtime && delaySec != null) Math.round(delaySec / 60.0).toInt() else null,
            isRealtime = realtime,
            shapeKey = s.str(8).orEmpty()
        )
    }
}

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

/**
 * A raw transitous segment (direct fallback when the server is unreachable) as a
 * [LiveSegment]; its geometry is added to [shapesOut] under the same key the
 * server would use.
 */
fun MapTripSegment.toLiveSegment(shapesOut: MutableMap<String, String>): LiveSegment? {
    val mode = mode ?: return null
    val dep = departure?.toEpochMillisOrNull() ?: return null
    val arr = arrival?.toEpochMillisOrNull() ?: return null
    if (arr <= dep) return null
    val key = polyline?.let { p -> GeoPolyline.shapeKey(p).also { shapesOut[it] = p } }.orEmpty()
    // Live delay = real-time departure minus the timetable departure (whole
    // minutes). Only meaningful when the trip is real-time-backed.
    val schedDep = scheduledDeparture?.toEpochMillisOrNull()
    val trip = trips.firstOrNull()
    return LiveSegment(
        tripId = trip?.tripId,
        label = trip?.routeShortName?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "?",
        mode = mode,
        colorHex = routeColor,
        fromName = from?.name,
        toName = to?.name,
        fromLat = from?.lat ?: Double.NaN,
        fromLon = from?.lon ?: Double.NaN,
        toLat = to?.lat ?: Double.NaN,
        toLon = to?.lon ?: Double.NaN,
        departureMs = dep,
        arrivalMs = arr,
        delayMinutes = if (realTime && schedDep != null) Math.round((dep - schedDep) / 60_000.0).toInt() else null,
        isRealtime = realTime,
        shapeKey = key
    )
}
