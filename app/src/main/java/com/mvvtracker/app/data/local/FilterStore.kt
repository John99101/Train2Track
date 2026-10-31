package com.mvvtracker.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's view choices, kept for the whole app and across restarts: the city,
 * the Feed filters and the Map's mode filter. Held in one place (not inside each
 * screen) so they no longer reset when switching tabs. Stored locally only; no
 * personal data. "Delete my data" clears them.
 */
@Singleton
class FilterStore @Inject constructor(
    @ApplicationContext context: Context
) {
    data class Filters(
        /**
         * Selected Feed filters by name (S-Bahn, U-Bahn, Bus, Tram, current reports);
         * empty = all. Until the user picks something: S-Bahn and U-Bahn.
         */
        val feedModes: Set<String> = DEFAULT_MODES,
        /** The single line picked in the Feed, valid only in [feedLineCity] ("S1" is another line elsewhere). */
        val feedLine: String? = null,
        val feedLineCity: String? = null,
        /** Modes shown on the Map by name; null = the default selection (S-Bahn and U-Bahn). */
        val mapModes: Set<String>? = null,
        /** The Map's "Stations" button: dots for the stations of the shown modes. Off until switched on. */
        val mapStations: Boolean = false,
        /** The city shown last (the user's location may still replace it at start). */
        val cityId: String? = null
    ) {
        /** The Feed's line filter while [cityId] is shown, else null (all lines). */
        fun feedLineIn(cityId: String): String? = feedLine?.takeIf { feedLineCity == cityId }
    }

    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)

    private val _filters = MutableStateFlow(
        Filters(
            // Nothing stored yet: the default. A stored empty set is the user's "All".
            feedModes = prefs.getStringSet(KEY_FEED_MODES, null)?.toSet() ?: DEFAULT_MODES,
            feedLine = prefs.getString(KEY_FEED_LINE, null),
            feedLineCity = prefs.getString(KEY_FEED_LINE_CITY, null),
            mapModes = prefs.getStringSet(KEY_MAP_MODES, null)?.toSet(),
            mapStations = prefs.getBoolean(KEY_MAP_STATIONS, false),
            cityId = prefs.getString(KEY_CITY, null)
        )
    )
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    fun setFeedModes(names: Set<String>) {
        _filters.value = _filters.value.copy(feedModes = names)
        // A fresh set instance (SharedPreferences ignores in-place edits).
        prefs.edit().putStringSet(KEY_FEED_MODES, HashSet(names)).apply()
    }

    /** [line] = null shows all lines again. */
    fun setFeedLine(cityId: String, line: String?) {
        _filters.value = _filters.value.copy(feedLine = line, feedLineCity = line?.let { cityId })
        prefs.edit().apply {
            if (line == null) remove(KEY_FEED_LINE).remove(KEY_FEED_LINE_CITY)
            else putString(KEY_FEED_LINE, line).putString(KEY_FEED_LINE_CITY, cityId)
        }.apply()
    }

    fun setMapModes(names: Set<String>) {
        _filters.value = _filters.value.copy(mapModes = names)
        prefs.edit().putStringSet(KEY_MAP_MODES, HashSet(names)).apply()
    }

    fun setMapStations(on: Boolean) {
        _filters.value = _filters.value.copy(mapStations = on)
        prefs.edit().putBoolean(KEY_MAP_STATIONS, on).apply()
    }

    fun setCity(cityId: String) {
        if (_filters.value.cityId == cityId) return
        _filters.value = _filters.value.copy(cityId = cityId)
        prefs.edit().putString(KEY_CITY, cityId).apply()
    }

    /** "Delete my data". */
    fun clear() {
        _filters.value = Filters()
        prefs.edit().clear().apply()
    }

    companion object {
        /** What the Feed and the Map show until the user picks: S-Bahn and U-Bahn. */
        val DEFAULT_MODES: Set<String> = setOf("SBAHN", "UBAHN")

        private const val KEY_FEED_MODES = "feed_modes"
        private const val KEY_FEED_LINE = "feed_line"
        private const val KEY_FEED_LINE_CITY = "feed_line_city"
        private const val KEY_MAP_MODES = "map_modes"
        private const val KEY_MAP_STATIONS = "map_stations"
        private const val KEY_CITY = "city"
    }
}
