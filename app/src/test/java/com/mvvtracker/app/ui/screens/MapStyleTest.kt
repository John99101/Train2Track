package com.mvvtracker.app.ui.screens

import com.mvvtracker.app.data.station.MapGroups
import com.mvvtracker.app.data.station.MapStation
import com.mvvtracker.app.data.station.MapStationsDto
import com.mvvtracker.app.data.station.toMapStations
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun stationDotsFollowTheZoom() {
        val rail = MapGroups.SBAHN or MapGroups.UBAHN or MapGroups.RAIL
        assertEquals(0, stationMaskFor(11.0))
        assertEquals(rail, stationMaskFor(START_ZOOM))
        assertEquals(rail or MapGroups.TRAM, stationMaskFor(13.5))
        assertEquals(rail or MapGroups.TRAM or MapGroups.BUS, stationMaskFor(14.5))
    }

    @Test
    fun mapStationsAreReadFromTheServerAnswer() {
        val dto = Json.decodeFromString<MapStationsDto>(
            """{"v":1,"city":"muenchen","complete":false,"stations":[
              ["Marienplatz",48.13706,11.57539,3,"de-DELFI_de:09162:2"],
              ["Kein Knopf",48.1,11.5,0,"x1"],
              ["",48.1,11.5,8,"x2"]]}"""
        )
        assertFalse(dto.complete)
        assertEquals(listOf(MapStation("de-DELFI_de:09162:2", "Marienplatz", 48.13706, 11.57539, 3)), dto.toMapStations())
        assertEquals(listOf("SUBURBAN", "SUBWAY"), MapGroups.modes(3))
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
