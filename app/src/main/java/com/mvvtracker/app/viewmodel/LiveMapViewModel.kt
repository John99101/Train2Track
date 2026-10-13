package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.live.LiveSegment
import com.mvvtracker.app.data.live.LiveTripsRepository
import com.mvvtracker.app.data.live.RouteStore
import com.mvvtracker.app.data.live.ShapeStore
import com.mvvtracker.app.data.live.routeKey
import com.mvvtracker.app.data.location.LocationCityProvider
import com.mvvtracker.app.data.model.CITY_OTHER
import com.mvvtracker.app.data.model.GermanCity
import com.mvvtracker.app.data.model.cityForView
import com.mvvtracker.app.data.model.reportCityAt
import com.mvvtracker.app.data.model.reportScopeFor
import com.mvvtracker.app.data.repository.ReportScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.abs

/** A single train, positioned at the current instant along its route segment. */
data class TrainPosition(
    val latitude: Double,
    val longitude: Double,
    val label: String,       // e.g. "S1", "U6", "RE5", "ICE"
    val mode: String,        // METRO | SUBWAY | REGIONAL_RAIL | HIGHSPEED_RAIL | ...
    val tripId: String? = null,    // identifies this exact train run
    val fromStop: String? = null,  // stop it last left
    val toStop: String? = null,    // stop it's heading to next
    val colorHex: String? = null,  // official line colour from the feed (6-hex)
    // Live delay in whole minutes vs the timetable (positive = late, negative =
    // early, 0 = on time). null when no scheduled time is known to compare against.
    val delayMinutes: Int? = null,
    // true = MOTIS has a live feed for this trip (position is real-time-backed);
    // false = pure timetable, i.e. "where it should be", not where it is.
    val isRealtime: Boolean = false,
    // The city a report on this train is filed under (a city id or CITY_OTHER),
    // see reportCityAt in GermanCity.kt.
    val city: String = CITY_OTHER
)

/**
 * Streams live vehicles for a whole city (the Feed, or a map view inside a city)
 * or for a free map box (a map view outside the supported cities).
 *
 * Two loops run:
 *  - a NETWORK loop that refreshes the vehicles via [LiveTripsRepository] (shared
 *    with the other tab, so switching tabs does not download again);
 *  - a TICK loop on a background thread that places every vehicle along its
 *    pre-decoded track ([ShapeStore]) — cheap, and never on the UI thread. The
 *    map then glides the markers between ticks (see LiveTrainMap).
 *
 * Both stop while the screen is hidden or the app is in the background
 * ([pause]/[resume]) to save battery and mobile data.
 */
@HiltViewModel
class LiveMapViewModel @Inject constructor(
    private val liveTrips: LiveTripsRepository,
    private val shapes: ShapeStore,
    private val routeStore: RouteStore,
    private val locationCityProvider: LocationCityProvider,
    private val reportScope: ReportScope
) : ViewModel() {

    data class Bounds(val north: Double, val south: Double, val east: Double, val west: Double) {
        fun expanded(factor: Double): Bounds {
            val dLat = (north - south) * factor
            val dLon = (east - west) * factor
            return Bounds(north + dLat, south - dLat, east + dLon, west - dLon)
        }
        fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
    }

    /** What is being shown: a whole city, or a free map box outside the cities. */
    private sealed interface Source {
        data class City(val city: GermanCity) : Source
        data class Box(val bounds: Bounds) : Source
    }

    private val source = MutableStateFlow<Source?>(null)
    /** The map's visible area; markers outside it (plus a margin) are not built. */
    private val viewport = MutableStateFlow<Bounds?>(null)
    private val active = MutableStateFlow(true)
    /** The Feed only lists vehicles, it needs no positions (and fewer ticks). */
    @Volatile private var positionsWanted = true

    /** The newest vehicles, and the city they were loaded for (null = a free map box). */
    private class Live(val segments: List<LiveSegment>, val cityId: String?)
    @Volatile private var live = Live(emptyList(), null)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _trains = MutableStateFlow<List<TrainPosition>>(emptyList())
    val trains: StateFlow<List<TrainPosition>> = _trains.asStateFlow()

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    /** The positions may lag: our server is unreachable or serves an older snapshot. */
    private val _dataDelayed = MutableStateFlow(false)
    val dataDelayed: StateFlow<Boolean> = _dataDelayed.asStateFlow()

    // Full line routes (key = routeKey(mode, label)). They come from the saved /
    // server route set of the city; a line missing there is asked from the server
    // once. Outside the supported cities no routes are drawn.
    private val routeByKey = ConcurrentHashMap<String, List<DoubleArray>>()
    private val routeInFlight = ConcurrentHashMap.newKeySet<String>()
    private val routeRetryAtMs = ConcurrentHashMap<String, Long>()   // a line the server had no route for
    private val routeSemaphore = Semaphore(5)          // cap parallel route requests
    @Volatile private var routeScope: String? = null   // city id, or "box:lat,lon"
    // False while the city's route set is still loading: until then every line looks missing.
    @Volatile private var routeSetReady = false
    @Volatile private var wantedRouteKeys: Set<String> = emptySet()   // what the Map last asked for
    private val _routes = MutableStateFlow<Map<String, List<DoubleArray>>>(emptyMap())
    val routes: StateFlow<Map<String, List<DoubleArray>>> = _routes.asStateFlow()

    /** Nearest supported city from the user's location (null until known/allowed). */
    private val _nearestCity = MutableStateFlow<GermanCity?>(null)
    val nearestCity: StateFlow<GermanCity?> = _nearestCity.asStateFlow()

    init {
        // Poll the user's location so the Map can center and the Feed can preselect
        // the right city (updates within seconds once permission is granted).
        viewModelScope.launch {
            while (isActive) {
                if (active.value) {
                    locationCityProvider.nearestCity()?.let { _nearestCity.value = it }
                }
                delay(LOCATION_MILLIS)
            }
        }
        // Network: refetch whenever the source changes, then periodically. Runs only
        // while the screen is on (see pause): pausing cancels the loop, resuming
        // starts it again and fetches at once if the last snapshot is too old.
        viewModelScope.launch {
            combine(source, active) { src, on -> if (on) src else null }
                .distinctUntilChanged()
                .collectLatest { src ->
                    if (src == null) return@collectLatest
                    // Load the reports of what is on screen (and of nothing else).
                    reportScope.show(src.reportCities())
                    // Only the map draws routes; the Feed's list does not need them.
                    if (positionsWanted) prepareRoutes(src)
                    // A free map box goes to transitous directly: wait until the map
                    // has stopped moving instead of asking once per pan.
                    if (src is Source.Box) delay(BOX_SETTLE_MILLIS)
                    while (isActive) {
                        val waitMs = try {
                            val snap = fetch(src)
                            live = Live(snap.segments, (src as? Source.City)?.city?.id)
                            _loadError.value = false
                            _dataDelayed.value = snap.delayed
                            wake.trySend(Unit)
                            when (src) {
                                is Source.City -> cityIntervalMs()
                                is Source.Box ->
                                    if (snap.area?.contains(src.area()) == true) LiveTripsRepository.BOX_REFRESH_MS
                                    // The map moved out of the fetched area too soon: ask again shortly.
                                    else LiveTripsRepository.BOX_MIN_GAP_MS
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _loadError.value = true
                            if (src is Source.City) cityIntervalMs() else LiveTripsRepository.BOX_MIN_GAP_MS
                        }
                        delay(waitMs)
                    }
                }
        }
        // Positions: recomputed on a background thread, early when new data arrives.
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (active.value) _trains.value = compute(System.currentTimeMillis())
                withTimeoutOrNull(if (positionsWanted) TICK_MILLIS else LIST_TICK_MILLIS) { wake.receive() }
            }
        }
    }

    /** Map: the visible area changed. Inside a city the whole city is used. */
    fun updateBounds(north: Double, south: Double, east: Double, west: Double) {
        val b = Bounds(north = north, south = south, east = east, west = west)
        viewport.value = b
        val city = cityForView(north, south, east, west)
        source.value = when {
            city != null -> Source.City(city)
            b.isReasonable() -> Source.Box(b)
            else -> source.value   // zoomed out too far: keep what is shown
        }
        wake.trySend(Unit)
    }

    /** Feed: list a whole city (no positions needed). */
    fun showCity(city: GermanCity) {
        positionsWanted = false
        viewport.value = null
        source.value = Source.City(city)
    }

    /** Called when the screen becomes visible (and the app is in the foreground): resume fetching/updating. */
    fun resume() { active.value = true; wake.trySend(Unit) }

    /** Called when the screen is hidden or the app goes to the background: stop fetching/updating. */
    fun pause() { active.value = false }

    /** The map refreshes every 20 s; the Feed's list does not need that and saves a third of the data. */
    private fun cityIntervalMs() = if (positionsWanted) FETCH_CITY_MILLIS else FETCH_FEED_MILLIS

    private suspend fun fetch(src: Source): LiveTripsRepository.Snapshot = when (src) {
        is Source.City -> liveTrips.cityTrips(src.city, maxAgeMs = cityIntervalMs() - 2_000)
        is Source.Box -> liveTrips.boxTrips(src.area())
    }

    private fun Source.Box.area() =
        LiveTripsRepository.Area(bounds.north, bounds.south, bounds.east, bounds.west)

    private fun Source.reportCities(): Set<String> = when (this) {
        is Source.City -> reportScopeFor(city)
        is Source.Box -> reportScopeFor(bounds.north, bounds.south, bounds.east, bounds.west)
    }

    private fun compute(now: Long): List<TrainPosition> {
        val current = live
        val segs = current.segments
        val withPositions = positionsWanted
        val area = if (withPositions) viewport.value?.expanded(VIEWPORT_MARGIN) else null
        val out = ArrayList<TrainPosition>(segs.size)
        val p = DoubleArray(2)
        for (s in segs) {
            if (s.mode !in SHOWN_MODES || now < s.departureMs || now > s.arrivalMs) continue
            if (!withPositions) {
                out += s.toTrain(0.0, 0.0, current.cityId ?: CITY_OTHER)
                continue
            }
            val fraction = (now - s.departureMs).toDouble() / (s.arrivalMs - s.departureMs).toDouble()
            val track = if (s.shapeKey.isNotEmpty()) shapes.track(s.shapeKey) else null
            when {
                track != null -> track.pointAt(fraction, p)
                // Geometry not downloaded yet: straight line between the two stops.
                s.hasStopCoordinates -> {
                    p[0] = s.fromLat + (s.toLat - s.fromLat) * fraction
                    p[1] = s.fromLon + (s.toLon - s.fromLon) * fraction
                }
                else -> continue
            }
            if (area != null && !area.contains(p[0], p[1])) continue
            // Inside a city every train is filed under that city; on a free map
            // box under the city its position lies in (or "other").
            out += s.toTrain(p[0], p[1], current.cityId ?: reportCityAt(p[0], p[1]))
        }
        return out
    }

    private fun LiveSegment.toTrain(lat: Double, lon: Double, city: String) = TrainPosition(
        latitude = lat,
        longitude = lon,
        label = label,
        mode = mode,
        tripId = tripId,
        fromStop = fromName,
        toStop = toName,
        colorHex = colorHex,
        delayMinutes = delayMinutes,
        isRealtime = isRealtime,
        city = city
    )

    // ---- Line routes ---------------------------------------------------------

    /** Switches the route set when the city (or the free map area) changes. */
    private suspend fun prepareRoutes(src: Source) {
        val scope = when (src) {
            is Source.City -> src.city.id
            is Source.Box -> {
                val lat = (src.bounds.north + src.bounds.south) / 2.0
                val lon = (src.bounds.east + src.bounds.west) / 2.0
                val prev = routeScope?.takeIf { it.startsWith("box:") }?.removePrefix("box:")?.split(",")
                    ?.mapNotNull { it.toDoubleOrNull() }
                // Keep the routes while panning around the same area.
                if (prev != null && prev.size == 2 && abs(prev[0] - lat) <= 0.25 && abs(prev[1] - lon) <= 0.25) return
                "box:$lat,$lon"
            }
        }
        if (scope == routeScope) return
        routeScope = scope
        routeSetReady = false
        routeByKey.clear()
        routeInFlight.clear()
        routeRetryAtMs.clear()
        _routes.value = emptyMap()
        if (src !is Source.City) return
        // Saved routes first (instant), then the server set if the saved one is old.
        routeByKey.putAll(routeStore.cached(scope))
        publishRoutes()
        viewModelScope.launch {
            val fresh = routeStore.refreshIfStale(scope)
            if (routeScope != scope) return@launch
            if (fresh != null) {
                routeByKey.putAll(fresh)
                publishRoutes()
            }
            // Only now is it known which lines the set lacks.
            routeSetReady = true
            ensureRoutes(wantedRouteKeys)
        }
    }

    private fun publishRoutes() { _routes.value = HashMap(routeByKey) }

    /**
     * Makes sure the routes for [keys] (see [routeKey]) the Map wants to draw are
     * there: a line missing from the city's route set is asked from the Train2Track
     * server once (see [RouteStore.fetchLine]), with a vehicle currently on that
     * line, and saved for the city. The app never asks the timetable service for a
     * route itself, so outside the supported cities no routes are drawn.
     */
    fun ensureRoutes(keys: Set<String>) {
        wantedRouteKeys = keys
        val scope = routeScope?.takeUnless { it.startsWith("box:") } ?: return
        // While the city's set is still loading, every line would look missing and be
        // asked for one by one: wait, prepareRoutes calls this again when it is there.
        if (!routeSetReady) return
        val segs = live.segments
        val now = System.currentTimeMillis()
        val reps = LinkedHashMap<String, LiveSegment>()   // key -> a vehicle on that line
        for (key in keys) {
            if (routeByKey.containsKey(key) || key in routeInFlight || reps.containsKey(key)) continue
            if (now < (routeRetryAtMs[key] ?: 0L)) continue
            val seg = segs.firstOrNull { it.tripId != null && routeKey(it.mode, it.label) == key } ?: continue
            reps[key] = seg
        }
        reps.forEach { (key, seg) ->
            routeInFlight.add(key)
            viewModelScope.launch {
                routeSemaphore.withPermit {
                    val pts = runCatching { routeStore.fetchLine(scope, seg.mode, seg.label, seg.tripId!!) }.getOrNull()
                    if (routeScope == scope) {
                        if (pts != null) {
                            routeByKey[key] = pts
                            publishRoutes()
                        } else {
                            // No route for this line right now: leave the server alone for a while.
                            routeRetryAtMs[key] = System.currentTimeMillis() + ROUTE_RETRY_MILLIS
                        }
                    }
                    routeInFlight.remove(key)
                }
            }
        }
    }

    private fun Bounds.isReasonable(): Boolean {
        val latSpan = abs(north - south)
        val lonSpan = abs(east - west)
        return latSpan in 0.0001..MAX_SPAN_DEGREES && lonSpan in 0.0001..MAX_SPAN_DEGREES
    }

    companion object {
        // Network refetch cadence. A city snapshot from the server is ~140 KB: the map
        // refreshes it every 20 s, the Feed (a list, no positions) every 30 s. A free
        // map box goes to transitous directly and is paced by LiveTripsRepository
        // (once a minute, and only after the map has stopped moving).
        const val FETCH_CITY_MILLIS = 20_000L
        const val FETCH_FEED_MILLIS = 30_000L
        const val BOX_SETTLE_MILLIS = 1_200L
        const val TICK_MILLIS = 1_000L       // map position recompute; markers glide in between
        const val LIST_TICK_MILLIS = 5_000L  // Feed: only which vehicles are running changes
        const val LOCATION_MILLIS = 8_000L   // nearest-city refresh cadence
        const val MAX_SPAN_DEGREES = 1.2
        const val VIEWPORT_MARGIN = 0.25     // build markers a bit beyond the screen edge
        const val ROUTE_RETRY_MILLIS = 5 * 60_000L         // a line without a route is asked again after this

        // Modes we surface in the app. Rail rapid transit + regional/long-distance,
        // plus (Phase 3) buses and trams.
        val SHOWN_MODES = setOf(
            "METRO",              // S-Bahn (S1..S8)
            "SUBWAY",             // U-Bahn (U1..U6)
            "TRAM",               // Straßenbahn
            "BUS",                // city/regional buses
            "REGIONAL_RAIL",
            "REGIONAL_FAST_RAIL",
            "HIGHSPEED_RAIL",
            "LONG_DISTANCE",
            "NIGHT_RAIL",
            "RAIL"
        )
    }
}
