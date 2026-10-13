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
 * built once a day on the server and kept on the device, so the map draws them
 * right away instead of fetching each line again on every visit.
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
     * unreachable (the saved copy and on-demand fetching keep working).
     */
    suspend fun refreshIfStale(cityId: String): Map<String, List<DoubleArray>>? = withContext(Dispatchers.IO) {
        val saved = read(cityId)
        if (saved != null && System.currentTimeMillis() - saved.fetchedAt < MAX_AGE_MS) return@withContext null
        val fresh = runCatching { routesApi.routes(cityId).routes }.getOrNull()
            ?.filter { it.size >= 4 && it[3].isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext null
        // Keep lines only fetched on demand that the server set does not have.
        val serverKeys = fresh.map { routeKey(it[1], it[0]) }.toSet()
        val extras = saved?.rows.orEmpty().filter { it.size >= 4 && routeKey(it[1], it[0]) !in serverKeys }
        val rows = fresh + extras
        write(cityId, SavedRoutes(System.currentTimeMillis(), rows))
        decode(rows)
    }

    /** Saves a route fetched on demand (a line the server set did not have yet). */
    suspend fun addOnDemand(cityId: String, mode: String, label: String, points: List<DoubleArray>) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val saved = read(cityId)
                val rows = saved?.rows.orEmpty().filterNot { it.size >= 2 && it[0] == label && it[1] == mode } +
                    listOf(listOf(label, mode, "", GeoPolyline.encode(points)))
                // fetchedAt 0 when there is no server copy yet, so it is still fetched.
                writeLocked(cityId, SavedRoutes(saved?.fetchedAt ?: 0L, rows))
            }
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
