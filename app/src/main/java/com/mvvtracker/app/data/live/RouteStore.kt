package com.mvvtracker.app.data.live

import android.content.Context
import com.mvvtracker.app.data.remote.GeoPolyline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Map key of a line route: mode and label, so bus "100" and tram "100" never mix. */
fun routeKey(mode: String, label: String) = "$mode|$label"

/**
 * A line's route: its pieces, each a list of [lat, lon] points. The first piece is
 * the whole route of one journey, the others only the parts other journeys add (a
 * branch, the other end of a short run). Older sets have one piece per line.
 */
typealias LineRoute = List<List<DoubleArray>>

/**
 * Full line routes per city (every S-Bahn, U-Bahn, tram, bus and regional line, with
 * their branches). The app ships with the routes of every city (assets/mapdata,
 * written by tools/bundle_map_data.js before a release), so the map draws them at
 * once and without a download. Once a day it asks the server whether the set
 * changed (a timetable change, a new line, a new branch): "not modified" costs no
 * data, a new set is saved on the device and used from then on. All routes come from the
 * Train2Track server: the app never asks the timetable service for one itself.
 */
@Singleton
class RouteStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routesApi: LiveRoutesService
) {
    @Serializable
    private data class SavedRoutes(val fetchedAt: Long, val rows: List<List<String>>, val etag: String? = null)

    /** The copy shipped with the app: the server's answer plus its ETag and when it was taken. */
    @Serializable
    private data class Bundled(val routes: List<List<String>> = emptyList(), val etag: String? = null, val fetchedAt: Long = 0)

    private val dir = File(context.filesDir, "live_routes")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val bundledByCity = ConcurrentHashMap<String, Optional<SavedRoutes>>()

    /** The city's routes on the phone, decoded (key = [routeKey]); empty when none. */
    suspend fun cached(cityId: String): Map<String, LineRoute> =
        withContext(Dispatchers.IO) { base(cityId)?.let { decode(it.rows) } ?: emptyMap() }

    /**
     * Asks the server whether the city's routes changed when the copy on the phone
     * is older than a day, has no ETag, or there is none; returns the new routes, or null when
     * nothing changed or the server is unreachable (the copy and [fetchLine] keep
     * working). With a copy the server only sends the set when it changed (ETag,
     * 304 without a body otherwise).
     */
    suspend fun refreshIfStale(cityId: String): Map<String, LineRoute>? = withContext(Dispatchers.IO) {
        val base = base(cityId)
        val now = System.currentTimeMillis()
        // A copy without an ETag was saved by an app version before the line branches
        // (one piece per line): ask at once, so the whole lines arrive.
        if (base != null && base.etag != null && now - base.fetchedAt < MAX_AGE_MS) return@withContext null
        val response = runCatching { routesApi.routes(cityId, base?.etag) }.getOrNull() ?: return@withContext null
        if (response.code() == 304 && base != null) {
            write(cityId, base.copy(fetchedAt = now))
            return@withContext null
        }
        val fresh = response.takeIf { it.isSuccessful }?.body()?.routes
            ?.filter { it.size >= 4 && it[3].isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null
        val rows = withExtras(fresh, base?.rows.orEmpty())
        write(cityId, SavedRoutes(now, rows, response.headers()["ETag"]))
        decode(rows)
    }

    /**
     * The route of one line the saved set does not have (a long distance train, a
     * night line), from the server, and saves it for the city. [tripId] is a
     * vehicle running on that line right now: the server builds the route from it
     * once for everybody. null when the server has none or cannot be reached.
     */
    suspend fun fetchLine(cityId: String, mode: String, label: String, tripId: String): LineRoute? =
        withContext(Dispatchers.IO) {
            val row = runCatching { routesApi.route(cityId, mode, label, tripId).route }.getOrNull()
                ?.takeIf { it.size >= 4 && it[3].isNotEmpty() }
                ?: return@withContext null
            val pieces = pieces(row).takeIf { it.isNotEmpty() } ?: return@withContext null
            lock.withLock {
                val base = base(cityId)
                val rows = base?.rows.orEmpty().filterNot { it.size >= 2 && it[0] == label && it[1] == mode } +
                    listOf(row)
                // fetchedAt 0 when there is no server copy yet, so it is still fetched.
                writeLocked(cityId, SavedRoutes(base?.fetchedAt ?: 0L, rows, base?.etag))
            }
            pieces
        }

    /**
     * The newest copy on the phone: the saved one, or the one shipped with the app
     * when that is newer (an app update brings newer routes). Lines the saved copy
     * got on demand and the newer one lacks are kept.
     */
    private fun base(cityId: String): SavedRoutes? {
        val saved = read(cityId)
        val bundled = bundled(cityId)
        return when {
            saved == null -> bundled
            bundled == null || saved.fetchedAt >= bundled.fetchedAt -> saved
            else -> bundled.copy(rows = withExtras(bundled.rows, saved.rows))
        }
    }

    /** [fresh] plus the lines of [old] it does not have (lines fetched on demand). */
    private fun withExtras(fresh: List<List<String>>, old: List<List<String>>): List<List<String>> {
        val keys = fresh.mapTo(HashSet()) { routeKey(it[1], it[0]) }
        return fresh + old.filter { it.size >= 4 && routeKey(it[1], it[0]) !in keys }
    }

    /** The routes shipped with the app for [cityId] (read once), or null when it has none. */
    private fun bundled(cityId: String): SavedRoutes? =
        bundledByCity.getOrPut(cityId) {
            Optional.ofNullable(
                runCatching {
                    val text = context.assets.open("mapdata/$cityId.routes.json").bufferedReader().use { it.readText() }
                    val b = json.decodeFromString<Bundled>(text)
                    SavedRoutes(b.fetchedAt, b.routes.filter { it.size >= 4 && it[3].isNotEmpty() }, b.etag)
                }.getOrNull()?.takeIf { it.rows.isNotEmpty() }
            )
        }.orElse(null)

    private fun decode(rows: List<List<String>>): Map<String, LineRoute> =
        rows.asSequence()
            .filter { it.size >= 4 && it[3].isNotEmpty() }
            .associate { routeKey(it[1], it[0]) to pieces(it) }
            .filterValues { it.isNotEmpty() }

    /** The pieces of a row [label, mode, colour, piece, piece...]. */
    private fun pieces(row: List<String>): LineRoute =
        row.drop(3).map { GeoPolyline.decode(it) }.filter { it.size >= 2 }

    private fun read(cityId: String): SavedRoutes? {
        val file = fileFor(cityId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<SavedRoutes>(file.readText()) }
            .onFailure { file.delete() }
            .getOrNull()
    }

    private suspend fun write(cityId: String, data: SavedRoutes) = lock.withLock { writeLocked(cityId, data) }

    private fun writeLocked(cityId: String, data: SavedRoutes) {
        // A full disk only means the set is not kept for next time.
        try {
            dir.mkdirs()
            val tmp = File(dir, "$cityId.json.tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(fileFor(cityId))) {
                fileFor(cityId).delete()
                tmp.renameTo(fileFor(cityId))
            }
        } catch (e: java.io.IOException) {
            // Not kept; the shipped copy or the server is used next time.
        }
    }

    private fun fileFor(cityId: String) = File(dir, "$cityId.json")

    private companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
