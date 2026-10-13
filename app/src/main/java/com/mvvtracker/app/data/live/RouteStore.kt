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
import javax.inject.Inject
import javax.inject.Singleton

/** Map key of a line route: mode and label, so bus "100" and tram "100" never mix. */
fun routeKey(mode: String, label: String) = "$mode|$label"

/**
 * Full line routes per city (every S-Bahn, U-Bahn, tram, bus and regional line),
 * kept by the server and saved on the device, so the map draws them right away
 * instead of fetching each line again on every visit. All routes come from the
 * Train2Track server: the app never asks the timetable service for one itself.
 */
@Singleton
class RouteStore @Inject constructor(
    @ApplicationContext context: Context,
    private val routesApi: LiveRoutesService
) {
    @Serializable
    private data class SavedRoutes(val fetchedAt: Long, val rows: List<List<String>>)

    private val dir = File(context.filesDir, "live_routes")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** The city's saved routes, decoded (key = [routeKey]); empty when none yet. */
    suspend fun cached(cityId: String): Map<String, List<DoubleArray>> =
        withContext(Dispatchers.IO) { read(cityId)?.let { decode(it.rows) } ?: emptyMap() }

    /**
     * Downloads the city's routes when the saved copy is missing or older than a
     * day; returns the new routes, or null when nothing changed or the server is
     * unreachable (the saved copy and [fetchLine] keep working).
     */
    suspend fun refreshIfStale(cityId: String): Map<String, List<DoubleArray>>? = withContext(Dispatchers.IO) {
        val saved = read(cityId)
        if (saved != null && System.currentTimeMillis() - saved.fetchedAt < MAX_AGE_MS) return@withContext null
        val fresh = runCatching { routesApi.routes(cityId).routes }.getOrNull()
            ?.filter { it.size >= 4 && it[3].isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null
        // Keep single lines fetched before that this copy of the set does not have.
        val serverKeys = fresh.map { routeKey(it[1], it[0]) }.toSet()
        val extras = saved?.rows.orEmpty().filter { it.size >= 4 && routeKey(it[1], it[0]) !in serverKeys }
        val rows = fresh + extras
        write(cityId, SavedRoutes(System.currentTimeMillis(), rows))
        decode(rows)
    }

    /**
     * The route of one line the saved set does not have (a long distance train, a
     * night line), from the server, and saves it for the city. [tripId] is a
     * vehicle running on that line right now: the server builds the route from it
     * once for everybody. null when the server has none or cannot be reached.
     */
    suspend fun fetchLine(cityId: String, mode: String, label: String, tripId: String): List<DoubleArray>? =
        withContext(Dispatchers.IO) {
            val row = runCatching { routesApi.route(cityId, mode, label, tripId).route }.getOrNull()
                ?.takeIf { it.size >= 4 && it[3].isNotEmpty() }
                ?: return@withContext null
            val points = GeoPolyline.decode(row[3]).takeIf { it.size >= 2 } ?: return@withContext null
            lock.withLock {
                val saved = read(cityId)
                val rows = saved?.rows.orEmpty().filterNot { it.size >= 2 && it[0] == label && it[1] == mode } +
                    listOf(listOf(label, mode, row[2], row[3]))
                // fetchedAt 0 when there is no server copy yet, so it is still fetched.
                writeLocked(cityId, SavedRoutes(saved?.fetchedAt ?: 0L, rows))
            }
            points
        }

    private fun decode(rows: List<List<String>>): Map<String, List<DoubleArray>> =
        rows.asSequence()
            .filter { it.size >= 4 && it[3].isNotEmpty() }
            .associate { routeKey(it[1], it[0]) to GeoPolyline.decode(it[3]) }
            .filterValues { it.size >= 2 }

    private fun read(cityId: String): SavedRoutes? {
        val file = fileFor(cityId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<SavedRoutes>(file.readText()) }
            .onFailure { file.delete() }
            .getOrNull()
    }

    private suspend fun write(cityId: String, data: SavedRoutes) = lock.withLock { writeLocked(cityId, data) }

    private fun writeLocked(cityId: String, data: SavedRoutes) {
        dir.mkdirs()
        val tmp = File(dir, "$cityId.json.tmp")
        tmp.writeText(json.encodeToString(data))
        if (!tmp.renameTo(fileFor(cityId))) {
            fileFor(cityId).delete()
            tmp.renameTo(fileFor(cityId))
        }
    }

    private fun fileFor(cityId: String) = File(dir, "$cityId.json")

    private companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
