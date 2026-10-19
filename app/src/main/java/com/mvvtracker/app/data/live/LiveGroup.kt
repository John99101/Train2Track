package com.mvvtracker.app.data.live

/**
 * The server sends a city's live data in three groups (functions/lib/liveGroups.js),
 * so the app downloads only what it shows: the default map needs rail only, about
 * an eighth of the whole city. iOS mirrors it (LiveGroup.swift).
 */
enum class LiveGroup(val path: String) {
    RAIL("rail"), TRAM("tram"), BUS("bus");

    companion object {
        val ALL: Set<LiveGroup> = entries.toSet()

        /** The group of a MOTIS mode, or null for a mode the app does not show. */
        fun of(mode: String): LiveGroup? = when (mode) {
            "METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL",
            "LONG_DISTANCE", "NIGHT_RAIL", "RAIL" -> RAIL
            "TRAM" -> TRAM
            "BUS" -> BUS
            else -> null
        }

        /** The groups needed to show [modes] (null = every mode). */
        fun forModes(modes: Set<String>?): Set<LiveGroup> =
            modes?.mapNotNullTo(HashSet()) { of(it) } ?: ALL
    }
}
