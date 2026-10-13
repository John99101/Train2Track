package com.mvvtracker.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleTest {

    @Test
    fun badgeSizeFollowsTheZoom() {
        assertEquals(ZoomTier.FAR, zoomTier(9.0))
        assertEquals(ZoomTier.FAR, zoomTier(11.49))
        assertEquals(ZoomTier.MID, zoomTier(11.5))
        assertEquals(ZoomTier.MID, zoomTier(13.49))
        assertEquals(ZoomTier.NEAR, zoomTier(13.5))
        assertEquals(ZoomTier.NEAR, zoomTier(18.0))
    }

    @Test
    fun aCityOpensWithSmallBadges() {
        assertEquals(ZoomTier.MID, zoomTier(START_ZOOM))
    }

    @Test
    fun drawOrderIsBusTramRailThenSBahnAndUBahn() {
        val order = listOf("BUS", "TRAM", "REGIONAL_RAIL", "METRO").map { drawRank(it) }
        assertEquals(order.sorted(), order)
        assertEquals(order.size, order.distinct().size)
        assertEquals(drawRank("METRO"), drawRank("SUBWAY"))
        assertEquals(drawRank("REGIONAL_RAIL"), drawRank("HIGHSPEED_RAIL"))
    }

    @Test
    fun aReportedVehicleIsOnTopWhateverItIs() {
        assertTrue(drawRank("BUS", flagged = true) > drawRank("METRO"))
        assertEquals(drawRank("BUS", flagged = true), drawRank("SUBWAY", flagged = true))
    }

    @Test
    fun linesGetThinnerWhenZoomedOut() {
        assertEquals(2f, lineWidthDp(ZoomTier.FAR), 0f)
        assertEquals(3f, lineWidthDp(ZoomTier.NEAR), 0f)
        assertTrue(lineWidthDp(ZoomTier.MID) in 2f..3f)
    }
}
