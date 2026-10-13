package com.mvvtracker.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Live map data source: **transitous** (https://transitous.org), a community-run
 * open instance of the MOTIS journey engine. No API key required.
 *
 * `/api/v1/map/trips` returns every public-transport trip segment active inside
 * a map bounding box during a time window, each with its two endpoint stops,
 * real-time departure/arrival timestamps, and the track geometry as an encoded
 * polyline. From a segment's [departure, arrival] window and the current time we
 * interpolate the vehicle's position along the polyline (see [GeoPolyline]) - so
 * trains move along their actual routes, updated every few seconds.
 *
 * The app only asks transitous itself when the Train2Track server does not answer
 * or when the map shows an area outside the supported cities (see
 * LiveTripsRepository). It asks for nothing else: line routes come from the
 * Train2Track server only.
 *
 * In the Munich data, modes map as: METRO = S-Bahn (S1..S8), SUBWAY = U-Bahn
 * (U1..U6), REGIONAL_RAIL = RE/RB, HIGHSPEED_RAIL/LONG_DISTANCE = ICE/IC.
 */
interface TransportApiService {

    @GET("api/v1/map/trips")
    suspend fun mapTrips(
        @Query("min") min: String,        // "lat,lon" of the south-west corner
        @Query("max") max: String,        // "lat,lon" of the north-east corner
        @Query("zoom") zoom: Int,
        @Query("startTime") startTime: String,  // ISO-8601 UTC, e.g. 2026-08-19T21:42:00Z
        @Query("endTime") endTime: String
    ): List<MapTripSegment>
}

@Serializable
data class MapTripSegment(
    val trips: List<MapTripRef> = emptyList(),
    val mode: String? = null,          // METRO | SUBWAY | REGIONAL_RAIL | HIGHSPEED_RAIL | ...
    val from: MapPlace? = null,
    val to: MapPlace? = null,
    val departure: String? = null,     // ISO-8601 UTC (real-time adjusted)
    val arrival: String? = null,
    // Planned times — present even when realTime is false. The gap between these
    // and departure/arrival is the live delay (Phase 6 / Option 1).
    val scheduledDeparture: String? = null,
    val scheduledArrival: String? = null,
    // true when MOTIS has a live feed for this trip; false = pure timetable (the
    // position is where the train *should* be, not a real-time-backed estimate).
    val realTime: Boolean = false,
    val polyline: String? = null,      // Google encoded polyline, precision 5
    val routeColor: String? = null     // official line colour, 6-digit hex (no '#')
)

@Serializable
data class MapTripRef(
    val tripId: String? = null,
    val routeShortName: String? = null // e.g. "S1", "U6", "RE5 (79404)", "ICE 1605"
)

@Serializable
data class MapPlace(
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null
)
