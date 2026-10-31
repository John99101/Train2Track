package com.mvvtracker.app.data.station

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The station dots of each city. The app ships with them (assets/mapdata, written
 * by tools/bundle_map_data.js before a release); once a week, and only while the
 * "Stations" button is on, it asks the server whether they changed ("not
 * modified" costs no data). Without a shipped copy (a city added later) they are
 * downloaded when the button is switched on, then kept: a week, or a day while the
 * server says its list is not complete yet. Public timetable data, no personal data.
 */
@Singleton
class MapStationStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: StationApiService
) {
    @Serializable
    private data class Saved(val fetchedAt: Long, val data: MapStationsDto, val etag: String? = null)

    /** The copy shipped with the app: the server's answer plus its ETag and when it was taken. */
    @Serializable
    private data class Bundled(
        val v: Int = 1,
        val city: String = "",
        val built: Long = 0,
        val complete: Boolean = true,
        val stations: List<JsonArray> = emptyList(),
        val etag: String? = null,
        val fetchedAt: Long = 0
    )

    private val dir = File(context.filesDir, "map_stations")
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, Saved>()
    /** When a download for a city last failed: not tried again before [RETRY_MS]. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The city's stations: the saved ones while fresh, else downloaded; the old ones when that fails. */
    suspend fun stations(cityId: String): List<MapStation> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val saved = memory[cityId] ?: newest(read(cityId), bundled(cityId))?.also { memory[cityId] = it }
        val maxAge = if (saved?.data?.complete == false) INCOMPLETE_MAX_AGE_MS else MAX_AGE_MS
        if (saved != null && now - saved.fetchedAt < maxAge) return@withContext saved.data.toMapStations()
        if (now - (failedAt[cityId] ?: 0L) < RETRY_MS) return@withContext saved?.data?.toMapStations().orEmpty()
        // With the saved copy's ETag the server answers 304 without a body when nothing changed.
        // A cancelled download (the button switched off meanwhile) is no failure.
        val response = try {
            api.mapStations(cityId, saved?.etag)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (response?.code() == 304 && saved != null) {
            val kept = saved.copy(fetchedAt = now)
            memory[cityId] = kept
            write(cityId, kept)
            return@withContext saved.data.toMapStations()
        }
        val fresh = response?.takeIf { it.isSuccessful }?.body()?.takeIf { it.stations.isNotEmpty() }
        if (fresh == null) {
            failedAt[cityId] = now
            return@withContext saved?.data?.toMapStations().orEmpty()
        }
        val entry = Saved(now, fresh, response.headers()["ETag"])
        memory[cityId] = entry
        write(cityId, entry)
        fresh.toMapStations()
    }

    private fun newest(saved: Saved?, bundled: Saved?): Saved? =
        if (saved == null || (bundled != null && bundled.fetchedAt > saved.fetchedAt)) bundled else saved

    /** The stations shipped with the app for [cityId], or null when it has none. */
    private fun bundled(cityId: String): Saved? = runCatching {
        val text = context.assets.open("mapdata/$cityId.stops.json").bufferedReader().use { it.readText() }
        val b = json.decodeFromString<Bundled>(text)
        Saved(b.fetchedAt, MapStationsDto(b.v, b.city, b.built, b.complete, b.stations), b.etag)
    }.getOrNull()?.takeIf { it.data.stations.isNotEmpty() }

    private fun read(cityId: String): Saved? {
        val file = File(dir, "$cityId.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<Saved>(file.readText()) }
            .onFailure { file.delete() }
            .getOrNull()
    }

    private fun write(cityId: String, entry: Saved) {
        try {
            writeFile(cityId, entry)
        } catch (e: java.io.IOException) {
            // A full disk only means the list is not kept for next time.
        }
    }

    private fun writeFile(cityId: String, entry: Saved) {
        dir.mkdirs()
        val tmp = File(dir, "$cityId.json.tmp")
        tmp.writeText(json.encodeToString(entry))
        val target = File(dir, "$cityId.json")
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val INCOMPLETE_MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 10L * 60 * 1000
    }
}
