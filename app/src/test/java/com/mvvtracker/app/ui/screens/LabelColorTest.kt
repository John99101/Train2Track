package com.mvvtracker.app.ui.screens

import com.mvvtracker.app.data.live.routeKey
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelColorTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun lightLinesGetBlackText() {
        assertEquals(black, labelColorOn(0xFFF7CE46.toInt()))   // Leipzig tram 2, yellow
        assertEquals(black, labelColorOn(0xFFFFFFFF.toInt()))
    }

    @Test
    fun darkLinesGetWhiteText() {
        assertEquals(white, labelColorOn(0xFF27398A.toInt()))   // dark blue
        assertEquals(white, labelColorOn(0xFFE30613.toInt()))   // München S4, red
        assertEquals(white, labelColorOn(0xFF000000.toInt()))
    }

    @Test
    fun onlyLocalLinesGetARouteLine() {
        assertTrue(LiveMapViewModel.hasRouteLine(routeKey("METRO", "S1")))
        assertTrue(LiveMapViewModel.hasRouteLine(routeKey("BUS", "100")))
        assertTrue(LiveMapViewModel.hasRouteLine(routeKey("REGIONAL_RAIL", "RE5")))
        assertFalse(LiveMapViewModel.hasRouteLine(routeKey("HIGHSPEED_RAIL", "ICE")))
        assertFalse(LiveMapViewModel.hasRouteLine(routeKey("LONG_DISTANCE", "IC")))
        assertFalse(LiveMapViewModel.hasRouteLine(routeKey("NIGHT_RAIL", "NJ")))
    }
}
