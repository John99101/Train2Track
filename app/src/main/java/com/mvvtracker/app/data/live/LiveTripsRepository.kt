package com.mvvtracker.app.data.live

import com.mvvtracker.app.data.model.GermanCity
import com.mvvtracker.app.data.remote.TransportApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where live vehicles come from, shared by the Feed and the Map so switching tabs
 * reuses the last snapshot instead of downloading it again.
 *
 *  - A city goes through the Train2Track server first (one compact snapshot for
 *    everyone, ~140 KB instead of ~1.2 MB); geometries come separately and are
 *    kept in [ShapeStore].
 *  - If the server is unreachable (offline, error) the app asks transitous
 *    directly, but gently: at most once every two minutes per city, so thousands
 *    of phones do not hit the community server every 20 s when ours is down.
 *    Such a snapshot is flagged [Snapshot.delayed].
 *  - A map view outside every supported city always asks transitous directly: a
 *    somewhat larger area than the screen, reused for a minute while the map stays
 *    inside it.
 */
@Singleton
class LiveTripsRepository @Inject constructor(
    private val liveApi: LiveApiService,
    private val transportApi: TransportApiService,
    private val shapes: ShapeStore
) {
    /** A map area in degrees. */
    data class Area(val north: Double, val south: Double, val east: Double, val west: Double) {
        fun contains(o: Area) = o.north <= north && o.south >= south && o.east <= east && o.west >= west

        /** Grown by [factor] of its size on every side, and to at least the given half sizes. */
        fun grown(factor: Double, minHalfLat: Double, minHalfLon: Double): Area {
            val halfLat = maxOf((north - south) / 2.0 * (1.0 + 2.0 * factor), minHalfLat)
            val halfLon = maxOf((east - west) / 2.0 * (1.0 + 2.0 * factor), minHalfLon)
            val lat = (north + south) / 2.0
            val lon = (east + west) / 2.0
            return Area(lat + halfLat, lat - halfLat, lon + halfLon, lon - halfLon)
        }
    }

    data class Snapshot(
        val segments: List<LiveSegment>,
        val fetchedAtMs: Long,
        /** The server could not reach transitous and sent its last snapshot. */
        val stale: Boolean,
        /** Our server did not answer: asked transitous directly, refreshed only every two minutes. */
        val viaFallback: Boolean = false,
        /** A free map box: the area this snapshot covers (null for a city). */
        val area: Area? = null
    ) {
        /** Worth telling the user: the positions may lag behind. */
        val delayed: Boolean get() = stale || viaFallback
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cityCache = ConcurrentHashMap<String, Snapshot>()
    private val cityLocks = ConcurrentHashMap<String, Mutex>()
    private val lastShapesFetch = ConcurrentHashMap<String, Long>()
    private val pacer = FallbackPacer()

    private val boxLock = Mutex()
    @Volatile private var boxCache: Snapshot? = null
    @Volatile private var lastBoxFetchMs = 0L

    /**
     * The city's vehicles, from cache when younger than [maxAgeMs]. Main-safe:
     * turning thousands of segments into objects (and hashing their geometries)
     * runs on a background thread, never on the UI thread.
     */
    suspend fun cityTrips(city: GermanCity, maxAgeMs: Long): Snapshot = withContext(Dispatchers.Default) {
        fresh(city.id, maxAgeMs)?.let { return@withContext it }
        cityLocks.getOrPut(city.id) { Mutex() }.withLock {
            fresh(city.id, maxAgeMs)?.let { return@withLock it }
            shapes.loadCity(city.id)
            val snap = viaServer(city) ?: fallback(city)
            cityCache[city.id] = snap
            snap
        }
    }

    /**
     * Vehicles for a free map view (outside the supported cities): always direct.
     * Fetches a larger area than [view] and reuses it for [BOX_REFRESH_MS] while
     * the view stays inside; after the map moved out of it, a new area is fetched
     * no sooner than [BOX_MIN_GAP_MS] after the last one (the old one is returned
     * meanwhile: check [Snapshot.area]). Main-safe.
     */
    suspend fun boxTrips(view: Area): Snapshot = withContext(Dispatchers.Default) {
        boxLock.withLock {
            val now = System.currentTimeMillis()
            val cached = boxCache
            if (cached != null) {
                val covers = cached.area?.contains(view) == true
                if (covers && now - cached.fetchedAtMs < BOX_REFRESH_MS) return@withLock cached
                if (now - lastBoxFetchMs < BOX_MIN_GAP_MS) return@withLock cached
            } else if (now - lastBoxFetchMs < BOX_MIN_GAP_MS) {
                throw IOException("Live data for this area is requested again shortly")
            }
            lastBoxFetchMs = now
            val wide = view.grown(BOX_MARGIN, BOX_MIN_HALF_LAT, BOX_MIN_HALF_LON)
            val snap = try {
                direct(wide.north, wide.south, wide.east, wide.west, cityId = null).copy(area = wide)
            } catch (e: HttpException) {
                // 422 = "too many trips" for the larger area: just what is on screen.
                if (e.code() != 422) throw e
                direct(view.north, view.south, view.east, view.west, cityId = null).copy(area = view)
            }
            boxCache = snap
            snap
        }
    }

    private fun fresh(cityId: String, maxAgeMs: Long): Snapshot? =
        cityCache[cityId]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < maxAgeMs }

    /** null when the server may not be asked right now, or did not answer properly. */
    private suspend fun viaServer(city: GermanCity): Snapshot? {
        if (!pacer.serverAllowed(System.currentTimeMillis())) return null
        return try {
            val dto = liveApi.live(city.id)
            val segments = dto.toSegments()
            if (segments.any { it.shapeKey.isNotEmpty() && !shapes.contains(it.shapeKey) }) {
                requestShapes(city.id)
            }
            Snapshot(segments, System.currentTimeMillis(), dto.stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pacer.onServerFailure(System.currentTimeMillis(), deviceOffline = e.isNoConnection())
            null
        }
    }

    /**
     * transitous directly, at most once every two minutes per city (see
     * [FallbackPacer]). In between the last direct snapshot keeps being shown.
     */
    private suspend fun fallback(city: GermanCity): Snapshot {
        val now = System.currentTimeMillis()
        if (!pacer.directAllowed(city.id, now)) {
            cityCache[city.id]
                ?.takeIf { it.viaFallback && now - it.fetchedAtMs < FALLBACK_USABLE_MS }
                ?.let { return it }
            throw IOException("Live data unavailable, trying again shortly")
        }
        pacer.onDirectRequest(city.id, now)
        return direct(city.north, city.south, city.east, city.west, city.id).copy(viaFallback = true)
    }

    /** No route to the host at all (offline, no DNS): the phone's problem, not the server's. */
    private fun Exception.isNoConnection(): Boolean =
        this is IOException && this !is SocketTimeoutException && this !is HttpException

    /**
     * Downloads the city's geometries in the background (at most every few minutes).
     * Until they arrive, vehicles on unknown hops move on a straight line.
     */
    private fun requestShapes(cityId: String) {
        val now = System.currentTimeMillis()
        if (now - (lastShapesFetch[cityId] ?: 0L) < SHAPES_MIN_INTERVAL_MS) return
        lastShapesFetch[cityId] = now
        scope.launch {
            runCatching { liveApi.shapes(cityId).shapes }
                .onSuccess { shapes.addAll(cityId, it) }
                .onFailure { lastShapesFetch[cityId] = now - SHAPES_MIN_INTERVAL_MS + SHAPES_RETRY_MS }
        }
    }

    private suspend fun direct(north: Double, south: Double, east: Double, west: Double, cityId: String?): Snapshot {
        val now = System.currentTimeMillis()
        val raw = transportApi.mapTrips(
            min = "$south,$west",
            max = "$north,$east",
            zoom = 14,
            startTime = Instant.ofEpochMilli(now - WINDOW_BEFORE_MS).toString(),
            endTime = Instant.ofEpochMilli(now + WINDOW_AFTER_MS).toString()
        )
        val geometries = HashMap<String, String>()
        val segments = raw.mapNotNull { it.toLiveSegment(geometries) }
        shapes.addAll(cityId, geometries.filterKeys { !shapes.contains(it) })
        return Snapshot(segments, now, stale = false)
    }

    companion object {
        /** A direct (fallback) snapshot may be shown this long (its window reaches 3 minutes ahead). */
        private const val FALLBACK_USABLE_MS = 170_000L
        /** Outside the cities: the same area is refreshed once a minute. */
        const val BOX_REFRESH_MS = 60_000L
        /** Outside the cities: a new area (the map moved away) is fetched at most this often. */
        const val BOX_MIN_GAP_MS = 15_000L
        private const val BOX_MARGIN = 0.15
        private const val BOX_MIN_HALF_LAT = 0.15
        private const val BOX_MIN_HALF_LON = 0.22
        private const val SHAPES_MIN_INTERVAL_MS = 10 * 60_000L
        private const val SHAPES_RETRY_MS = 60_000L
        // A segment is listed when it is under way at any time in this window, so a
        // snapshot stays usable for up to WINDOW_AFTER_MS (longer than any reuse above).
        private const val WINDOW_BEFORE_MS = 60_000L
        private const val WINDOW_AFTER_MS = 180_000L
    }
}
