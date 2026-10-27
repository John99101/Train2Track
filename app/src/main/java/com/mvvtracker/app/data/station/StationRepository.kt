package com.mvvtracker.app.data.station

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Station search and boards from the server (see [StationApiService]). */
@Singleton
class StationRepository @Inject constructor(private val api: StationApiService) {
    suspend fun search(cityId: String, text: String): List<StationRef> {
        val q = normalizeStationQuery(text) ?: return emptyList()
        return api.search(cityId, q).toRefs(cityId)
    }

    suspend fun board(station: StationRef): StationBoard = api.station(station.cityId, station.id).toBoard()
}

/**
 * The stations the rider opened last, newest first, at most [MAX]. Stored on the
 * phone only; "Delete my data" clears them.
 */
@Singleton
class StationRecents @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("stations", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<StationRef>> = _recent.asStateFlow()

    fun opened(station: StationRef) {
        val list = (listOf(station) + _recent.value.filterNot { it.id == station.id && it.cityId == station.cityId }).take(MAX)
        _recent.value = list
        prefs.edit().putString(KEY, list.joinToString("\n") { "${it.cityId}\t${it.id}\t${it.name}\t${it.modes.joinToString(",")}" }).apply()
    }

    fun clear() {
        _recent.value = emptyList()
        prefs.edit().clear().apply()
    }

    private fun load(): List<StationRef> =
        prefs.getString(KEY, null).orEmpty().lines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 3 || p[1].isBlank()) null
            else StationRef(p[1], p[2], p[0], p.getOrNull(3).orEmpty().split(",").filter { it.isNotBlank() })
        }

    private companion object {
        const val KEY = "recent"
        const val MAX = 8
    }
}
