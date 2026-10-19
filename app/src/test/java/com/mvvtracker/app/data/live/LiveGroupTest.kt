package com.mvvtracker.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Must match GROUP_MODES in functions/lib/liveGroups.js. */
class LiveGroupTest {

    @Test
    fun everyShownModeHasTheServersGroup() {
        for (m in listOf("METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL")) {
            assertEquals(m, LiveGroup.RAIL, LiveGroup.of(m))
        }
        assertEquals(LiveGroup.TRAM, LiveGroup.of("TRAM"))
        assertEquals(LiveGroup.BUS, LiveGroup.of("BUS"))
        assertNull(LiveGroup.of("FERRY"))
    }

    @Test
    fun theDefaultMapNeedsRailOnly() {
        val sbahnUbahnRail = setOf("METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL")
        assertEquals(setOf(LiveGroup.RAIL), LiveGroup.forModes(sbahnUbahnRail))
    }

    @Test
    fun allModesNeedEveryGroup() {
        assertEquals(LiveGroup.ALL, LiveGroup.forModes(null))
        assertEquals(setOf(LiveGroup.RAIL, LiveGroup.BUS), LiveGroup.forModes(setOf("METRO", "BUS")))
        assertEquals(emptySet<LiveGroup>(), LiveGroup.forModes(emptySet()))
    }

    @Test
    fun serverPaths() {
        assertEquals(listOf("rail", "tram", "bus"), LiveGroup.entries.map { it.path })
    }
}
