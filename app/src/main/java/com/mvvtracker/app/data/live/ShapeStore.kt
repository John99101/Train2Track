package com.mvvtracker.app.data.live

import android.content.Context
import com.mvvtracker.app.data.remote.GeoPolyline
import com.mvvtracker.app.data.remote.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Track geometries by key, kept for good: each hop between two stops is decoded
 * once and reused on every position tick (decoding every polyline every 2 s on the
 * UI thread was the map's stutter), and saved per city so the geometries are
 * simply there on the next app start instead of being downloaded again.
 */
@Singleton
class ShapeStore @Inject constructor(@ApplicationContext context: Context) {

    private val dir = File(context.filesDir, "live_shapes")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    /** Every known geometry, key -> encoded polyline. */
    private val encoded = ConcurrentHashMap<String, String>()
    /** Keys saved in each city's file. */
    private val cityKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    /** Decoded tracks, least recently used evicted first. */
    private val tracks = object : LinkedHashMap<String, Track>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > MAX_TRACKS
    }

    fun contains(key: String): Boolean = encoded.containsKey(key)

    /** The decoded track for [key], decoding it on first use; null when unknown. */
    fun track(key: String): Track? {
        synchronized(tracks) { tracks[key] }?.let { return it }
        val poly = encoded[key] ?: return null
        val track = Track.of(GeoPolyline.decode(poly)) ?: return null
        synchronized(tracks) { tracks[key] = track }
        return track
    }

    /** Loads a city's saved geometries (once per app run). */
    suspend fun loadCity(cityId: String) {
        if (!loaded.add(cityId)) return
        withContext(Dispatchers.IO) {
            val file = fileFor(cityId)
            if (!file.exists()) return@withContext
            runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }
                .onSuccess { saved ->
                    encoded.putAll(saved)
                    cityKeys.getOrPut(cityId) { ConcurrentHashMap.newKeySet() }.addAll(saved.keys)
                }
                .onFailure { file.delete() }   // corrupt cache: start over
        }
    }

    /** Adds geometries; with a [cityId] they are also saved in that city's file. */
    suspend fun addAll(cityId: String?, shapes: Map<String, String>) {
        if (shapes.isEmpty()) return
        encoded.putAll(shapes)
        if (cityId == null) return
        val keys = cityKeys.getOrPut(cityId) { ConcurrentHashMap.newKeySet() }
        if (keys.containsAll(shapes.keys)) return
        keys.addAll(shapes.keys)
        // A city collects new hops over time; past a cap start again from the latest set.
        if (keys.size > MAX_PER_CITY) {
            keys.clear()
            keys.addAll(shapes.keys)
        }
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val snapshot = keys.mapNotNull { k -> encoded[k]?.let { k to it } }.toMap()
                // A full disk only means the tracks are not kept for next time.
                try {
                    dir.mkdirs()
                    val tmp = File(dir, "$cityId.json.tmp")
                    tmp.writeText(json.encodeToString(snapshot))
                    if (!tmp.renameTo(fileFor(cityId))) {
                        fileFor(cityId).delete()
                        tmp.renameTo(fileFor(cityId))
                    }
                } catch (e: java.io.IOException) {
                    // Not kept; downloaded again next time.
                }
            }
        }
    }

    private fun fileFor(cityId: String) = File(dir, "$cityId.json")

    private companion object {
        const val MAX_TRACKS = 15_000
        const val MAX_PER_CITY = 30_000
    }
}
