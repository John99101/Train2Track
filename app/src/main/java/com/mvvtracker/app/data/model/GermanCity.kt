package com.mvvtracker.app.data.model

/** Deutsche Bahn ticket shop — nationwide default when a city has no local operator. */
const val DB_TICKETS_URL = "https://www.bahn.de/"

/**
 * Half-size (degrees) of a city's region: large enough to cover the WHOLE S-/U-Bahn
 * network around the city, not just the centre (e.g. Munich's S-Bahn runs out to
 * Freising, Tutzing, Erding…). The server proxy uses the same region per city
 * (functions/lib/cities.js), so keep both in sync.
 */
const val CITY_HALF_LAT = 0.35
const val CITY_HALF_LON = 0.50

/**
 * A German city with an S-Bahn and U-Bahn network, used by the Feed screen's
 * city picker. Coordinates are the city centre the live map is centred on.
 * [id] is the city's key on the Train2Track server (/api/live/{id}).
 * [ticketUrl] is the local operator's ticket page (opens their app if installed,
 * else the browser); it defaults to Deutsche Bahn.
 */
data class GermanCity(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val ticketUrl: String = DB_TICKETS_URL
) {
    val north get() = latitude + CITY_HALF_LAT
    val south get() = latitude - CITY_HALF_LAT
    val east get() = longitude + CITY_HALF_LON
    val west get() = longitude - CITY_HALF_LON

    /** True when the box lies completely inside this city's region. */
    fun regionContains(north: Double, south: Double, east: Double, west: Double): Boolean =
        north <= this.north && south >= this.south && east <= this.east && west >= this.west
}

/**
 * Cities offered in the Feed picker. First entry (München) is the default.
 * Ticket URLs point at the local operator's ticket page (verify/refine as needed;
 * anything without a local override falls back to Deutsche Bahn).
 */
val GERMAN_CITIES: List<GermanCity> = listOf(
    GermanCity("muenchen", "München", 48.1372, 11.5755, "https://www.mvg.de/abos-tickets.html"),
    GermanCity("berlin", "Berlin", 52.5200, 13.4050, "https://www.bvg.de/de/abos-und-tickets/tickets-kaufen"),
    GermanCity("hamburg", "Hamburg", 53.5511, 9.9937, "https://www.hvv.de/de/onlineshop"),
    GermanCity("frankfurt", "Frankfurt", 50.1109, 8.6821, "https://www.rmv.de/c/de/tickets"),
    GermanCity("nuernberg", "Nürnberg", 49.4521, 11.0767, "https://www.vgn.de/tickets/"),
    GermanCity("stuttgart", "Stuttgart", 48.7758, 9.1829, "https://www.vvs.de/tickets-und-abos/einzelticket"),
    GermanCity("koeln", "Köln", 50.9375, 6.9603, "https://www.kvb.koeln/abos-und-tickets/alle-tickets/index.html"),
    GermanCity("duesseldorf", "Düsseldorf", 51.2277, 6.7735, "https://www.rheinbahn.de/tickets-und-tarife"),
    GermanCity("dortmund", "Dortmund", 51.5136, 7.4653, "https://www.bus-und-bahn.de/tickets")
)

/**
 * The supported city whose region fully contains the box, preferring the one whose
 * centre is closest to the box centre (regions overlap in the Ruhr area); null when
 * the box is outside every region (or bigger than one).
 */
fun cityForBox(north: Double, south: Double, east: Double, west: Double): GermanCity? {
    val cLat = (north + south) / 2.0
    val cLon = (east + west) / 2.0
    return GERMAN_CITIES
        .filter { it.regionContains(north, south, east, west) }
        .minByOrNull { (it.latitude - cLat) * (it.latitude - cLat) + (it.longitude - cLon) * (it.longitude - cLon) }
}

/**
 * The city the map shows. Normally the one whose region fully contains the visible
 * box ([cityForBox]). A box too large to fit into any region (the map is zoomed
 * out) still belongs to the city its centre lies in: zooming out then keeps that
 * city's vehicles and route lines, and the app does not have to ask the timetable
 * service itself. null when the map shows an area outside the cities.
 */
fun cityForView(north: Double, south: Double, east: Double, west: Double): GermanCity? {
    cityForBox(north, south, east, west)?.let { return it }
    val largerThanARegion = north - south > 2 * CITY_HALF_LAT || east - west > 2 * CITY_HALF_LON
    if (!largerThanARegion) return null
    val cLat = (north + south) / 2.0
    val cLon = (east + west) / 2.0
    return GERMAN_CITIES
        .filter { cLat in it.south..it.north && cLon in it.west..it.east }
        .minByOrNull { (it.latitude - cLat) * (it.latitude - cLat) + (it.longitude - cLon) * (it.longitude - cLon) }
}

// ---- Reports per city -------------------------------------------------------
//
// Every report, delay vote and log entry carries the city the train was seen in,
// and each app only loads the reports of the cities it is showing (instead of
// every report in Germany). iOS and the server use the same ids
// (ios/.../GermanCity.swift, firestore.rules); keep them in sync.

/** A train outside every supported city (a map view somewhere else). */
const val CITY_OTHER = "other"

/** Set by the server for app versions that send no city; every app loads these too. */
const val CITY_UNKNOWN = "unknown"

/** Regions this close (degrees) still count as touching: a train can be a little outside its city's box. */
private const val SCOPE_MARGIN = 0.15

/**
 * The city a train at this position is filed under: the supported city whose
 * region contains it (the nearest centre where regions overlap), else [CITY_OTHER].
 */
fun reportCityAt(latitude: Double, longitude: Double): String =
    GERMAN_CITIES
        .filter { latitude in it.south..it.north && longitude in it.west..it.east }
        .minByOrNull {
            (it.latitude - latitude) * (it.latitude - latitude) + (it.longitude - longitude) * (it.longitude - longitude)
        }
        ?.id ?: CITY_OTHER

/** Ids of the cities whose region touches the box. */
private fun citiesTouching(north: Double, south: Double, east: Double, west: Double): List<String> =
    GERMAN_CITIES
        .filter {
            it.south - SCOPE_MARGIN <= north && it.north + SCOPE_MARGIN >= south &&
                it.west - SCOPE_MARGIN <= east && it.east + SCOPE_MARGIN >= west
        }
        .map { it.id }

/**
 * The report cities an app showing [city] must load: the city itself, every city
 * whose region overlaps it (the same train can be filed under either, as in the
 * Ruhr area) and [CITY_UNKNOWN].
 */
fun reportScopeFor(city: GermanCity): Set<String> =
    (citiesTouching(city.north, city.south, city.east, city.west) + CITY_UNKNOWN).toSortedSet()

/** Same for a free map box: every city it touches, [CITY_OTHER] and [CITY_UNKNOWN]. */
fun reportScopeFor(north: Double, south: Double, east: Double, west: Double): Set<String> =
    (citiesTouching(north, south, east, west) + CITY_OTHER + CITY_UNKNOWN).toSortedSet()
