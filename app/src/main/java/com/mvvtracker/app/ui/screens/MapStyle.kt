package com.mvvtracker.app.ui.screens

/**
 * How close the map is. It decides the size of the vehicle badges (full badge,
 * small badge, dot) and the width of the route lines.
 */
internal enum class ZoomTier { FAR, MID, NEAR }

/** The zoom the map opens a city with. */
internal const val START_ZOOM = 12.0

// On a phone the map shows about 39 km across at 11.5 and about 10 km at 13.5.
private const val MID_ZOOM = 11.5
private const val NEAR_ZOOM = 13.5

internal fun zoomTier(zoom: Double): ZoomTier = when {
    zoom >= NEAR_ZOOM -> ZoomTier.NEAR
    zoom >= MID_ZOOM -> ZoomTier.MID
    else -> ZoomTier.FAR
}

/**
 * Draw order of vehicles and route lines, lowest first: bus, tram, rail, S-Bahn
 * and U-Bahn. A reported vehicle is always on top.
 */
internal fun drawRank(mode: String, flagged: Boolean = false): Int = when {
    flagged -> 4
    mode == "METRO" || mode == "SUBWAY" -> 3
    mode == "TRAM" -> 1
    mode == "BUS" -> 0
    else -> 2
}

/** Width of a route line, without its white edge. */
internal fun lineWidthDp(tier: ZoomTier): Float = when (tier) {
    ZoomTier.FAR -> 2f
    ZoomTier.MID -> 2.5f
    ZoomTier.NEAR -> 3f
}
