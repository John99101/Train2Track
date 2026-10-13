package com.mvvtracker.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoPolylineTest {

    @Test
    fun decodesKnownGooglePolyline() {
        // The canonical example from Google's encoded-polyline documentation.
        val points = GeoPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0][0], 1e-5)
        assertEquals(-120.2, points[0][1], 1e-5)
        assertEquals(40.7, points[1][0], 1e-5)
        assertEquals(-120.95, points[1][1], 1e-5)
        assertEquals(43.252, points[2][0], 1e-5)
        assertEquals(-126.453, points[2][1], 1e-5)
    }

    @Test
    fun interpolatesEndpointsAndMidpoint() {
        val line = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 10.0))
        assertEquals(0.0, GeoPolyline.pointAtFraction(line, 0.0)!![1], 1e-9)
        assertEquals(10.0, GeoPolyline.pointAtFraction(line, 1.0)!![1], 1e-9)
        // At the equator, distance is proportional to longitude, so 0.5 -> 5.0.
        assertEquals(5.0, GeoPolyline.pointAtFraction(line, 0.5)!![1], 1e-6)
    }

    @Test
    fun emptyLineHasNoPosition() {
        assertNull(GeoPolyline.pointAtFraction(emptyList(), 0.5))
    }

    @Test
    fun encodeIsTheInverseOfDecode() {
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertEquals(encoded, GeoPolyline.encode(GeoPolyline.decode(encoded)))
    }

    @Test
    fun shapeKeyMatchesTheServer() {
        // Same value as shapeKey() in functions/lib/transitous.js for this polyline.
        assertEquals("O50N0pk_msaQ", GeoPolyline.shapeKey("_p~iF~ps|U_ulLnnqC_mqNvxq`@"))
    }

    @Test
    fun trackMatchesPointAtFraction() {
        val points = GeoPolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val track = Track.of(points)!!
        val out = DoubleArray(2)
        for (f in listOf(0.0, 0.1, 0.33, 0.5, 0.77, 1.0)) {
            track.pointAt(f, out)
            val expected = GeoPolyline.pointAtFraction(points, f)!!
            assertEquals(expected[0], out[0], 1e-9)
            assertEquals(expected[1], out[1], 1e-9)
        }
    }
}
