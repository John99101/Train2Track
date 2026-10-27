package com.mvvtracker.app.data.model

import com.mvvtracker.app.data.live.LiveTripsRepository.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reports per city: where a train is filed, and which cities a view must load so
 * no report on a train it shows is missed. iOS mirrors these cases (GermanCity.swift).
 */
class ReportCityTest {

    private fun city(id: String) = GERMAN_CITIES.first { it.id == id }

    @Test
    fun `a train in a city centre is filed under that city`() {
        GERMAN_CITIES.forEach { assertEquals(it.id, reportCityAt(it.latitude, it.longitude)) }
    }

    @Test
    fun `a train outside every region is filed under other`() {
        assertEquals(CITY_OTHER, reportCityAt(51.0504, 13.7373))   // Dresden
        assertEquals(CITY_OTHER, reportCityAt(51.3127, 9.4797))    // Kassel
    }

    @Test
    fun `where regions overlap the nearest centre wins`() {
        // Leverkusen lies in the regions of Köln and Düsseldorf, closer to Köln.
        assertEquals("koeln", reportCityAt(51.0459, 6.9853))
        // Duisburg: Düsseldorf and Dortmund regions, closer to Düsseldorf.
        assertEquals("duesseldorf", reportCityAt(51.4344, 6.7623))
    }

    @Test
    fun `a single city loads itself and unknown`() {
        assertEquals(setOf("muenchen", CITY_UNKNOWN), reportScopeFor(city("muenchen")))
        assertEquals(setOf("berlin", CITY_UNKNOWN), reportScopeFor(city("berlin")))
        assertEquals(setOf("frankfurt", CITY_UNKNOWN), reportScopeFor(city("frankfurt")))
    }

    @Test
    fun `the Ruhr cities load each other`() {
        val ruhr = setOf("koeln", "duesseldorf", "dortmund", CITY_UNKNOWN)
        assertEquals(ruhr, reportScopeFor(city("koeln")))
        assertEquals(ruhr, reportScopeFor(city("duesseldorf")))
        assertEquals(ruhr, reportScopeFor(city("dortmund")))
    }

    @Test
    fun `every train a city shows is filed under a city in its scope`() {
        // Sample each city's whole region: wherever a train is, the city it is
        // filed under must be one this city loads.
        GERMAN_CITIES.forEach { c ->
            val scope = reportScopeFor(c)
            var lat = c.south
            while (lat <= c.north) {
                var lon = c.west
                while (lon <= c.east) {
                    assertTrue("${c.id} misses ${reportCityAt(lat, lon)} at $lat,$lon", reportCityAt(lat, lon) in scope)
                    lon += 0.05
                }
                lat += 0.05
            }
        }
    }

    @Test
    fun `a free map box loads the cities it touches, other and unknown`() {
        // Around Dresden: no supported city nearby.
        assertEquals(setOf(CITY_OTHER, CITY_UNKNOWN), reportScopeFor(51.2, 50.9, 13.95, 13.5))
        // A box between Köln and Frankfurt that reaches into Köln's region.
        val scope = reportScopeFor(50.75, 50.45, 7.6, 7.2)
        assertTrue("koeln" in scope)
        assertTrue(CITY_OTHER in scope)
        assertFalse("muenchen" in scope)
    }

    @Test
    fun `the scope never exceeds what one Firestore query may ask for`() {
        // whereIn takes at most 30 values.
        GERMAN_CITIES.forEach { assertTrue(reportScopeFor(it).size <= 30) }
        assertTrue(reportScopeFor(55.0, 47.0, 15.0, 6.0).size <= 30)   // all of Germany
    }

    @Test
    fun `a fetched map area is larger than the view and never tiny`() {
        val view = Area(north = 51.36, south = 51.32, east = 12.42, west = 12.34)
        val wide = view.grown(factor = 0.15, minHalfLat = 0.15, minHalfLon = 0.22)
        assertTrue(wide.contains(view))
        assertEquals(0.30, wide.north - wide.south, 1e-9)
        assertEquals(0.44, wide.east - wide.west, 1e-9)
        // A big view grows by the factor on every side.
        val big = Area(north = 52.0, south = 51.0, east = 13.0, west = 12.0)
        val grown = big.grown(factor = 0.15, minHalfLat = 0.15, minHalfLon = 0.22)
        assertEquals(1.30, grown.north - grown.south, 1e-9)
        assertTrue(grown.contains(big))
        assertFalse(big.contains(grown))
    }

    @Test
    fun `a zoomed out map stays with the city its centre lies in`() {
        val m = city("muenchen")
        // A view inside the region: that city, as before.
        assertEquals("muenchen", cityForView(m.latitude + 0.2, m.latitude - 0.2, m.longitude + 0.2, m.longitude - 0.2)?.id)
        // Zoomed out: taller than the region, centred a little off the centre.
        assertEquals("muenchen", cityForView(m.latitude + 0.6, m.latitude - 0.4, m.longitude + 0.4, m.longitude - 0.4)?.id)
        // Zoomed out over the Ruhr area, where regions overlap: the nearest centre.
        val d = city("dortmund")
        assertEquals("dortmund", cityForView(d.latitude + 1.0, d.latitude - 1.0, d.longitude + 1.0, d.longitude - 1.0)?.id)
    }

    @Test
    fun `a view outside the cities belongs to no city`() {
        // Zoomed out around Dresden.
        assertEquals(null, cityForView(51.6, 50.4, 14.4, 13.1))
        // A view of normal size that sticks out of the region is a free map area, as before.
        val m = city("muenchen")
        assertEquals(null, cityForView(m.north + 0.1, m.north - 0.3, m.longitude + 0.2, m.longitude - 0.2))
        assertEquals(null, cityForBox(m.latitude + 0.6, m.latitude - 0.4, m.longitude + 0.4, m.longitude - 0.4))
    }
}
