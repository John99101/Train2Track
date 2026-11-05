package com.mvvtracker.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMarkTest {

    @Test
    fun colourFollowsHowManyRidersReport() {
        assertEquals(ReportOrange, reportColor(1))
        assertEquals(ReportLightRed, reportColor(2))
        assertEquals(ReportLightRed, reportColor(5))
        assertEquals(ReportRed, reportColor(6))
        assertEquals(ReportRed, reportColor(10))
        assertEquals(ReportBrightRed, reportColor(11))
    }

    @Test
    fun theWhiteMarkStaysReadableOnEveryColour() {
        // 3:1 is the WCAG minimum for large or bold text; the "!" is bold.
        for (c in listOf(ReportOrange, ReportLightRed, ReportRed, ReportBrightRed)) {
            val contrast = (Color.White.luminance() + 0.05f) / (c.luminance() + 0.05f)
            assertTrue("contrast $contrast on $c", contrast >= 3f)
        }
    }
}
