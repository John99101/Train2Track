package com.mvvtracker.app.ui.screens

import com.mvvtracker.app.data.station.MapGroups

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

// Station dots ("Stations" button): rail stations from a city view on, tram stops
// closer, bus stops only on a few streets' view (there are thousands of them).
private const val BUS_STOP_ZOOM = 14.5

/** The station kinds (MapGroups bits) the zoom shows a dot for. */
internal fun stationMaskFor(zoom: Double): Int {
    var mask = 0
    if (zoom >= MID_ZOOM) mask = mask or MapGroups.SBAHN or MapGroups.UBAHN or MapGroups.RAIL
    if (zoom >= NEAR_ZOOM) mask = mask or MapGroups.TRAM
    if (zoom >= BUS_STOP_ZOOM) mask = mask or MapGroups.BUS
    return mask
}

/** Radius of a station dot, without its ring. */
internal fun stationRadiusDp(tier: ZoomTier): Float = if (tier == ZoomTier.NEAR) 4.5f else 3f

internal const val STATION_RING_DP = 1.5f
/** How far from a dot a tap still opens its station. */
internal const val STATION_TAP_DP = 16f
/** The dark ring of a station dot (white inside), readable on the light and the dark map. */
internal const val STATION_RING_ARGB = 0xFF3C3C3C

/** Width of a route line, without its white edge. */
internal fun lineWidthDp(tier: ZoomTier): Float = when (tier) {
    ZoomTier.FAR -> 2f
    ZoomTier.MID -> 2.5f
    ZoomTier.NEAR -> 3f
}

/**
 * The colour of a line name written on [background]: black on light colours (a
 * yellow line), white otherwise. The same rule as iOS (`contrastingLabel`).
 */
internal fun labelColorOn(background: Int): Int {
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (luminance > 0.6) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
}
