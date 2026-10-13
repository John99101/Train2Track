package com.mvvtracker.app.data.live

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must read exactly what the server writes. The fixture below is real
 * output of compactLive() (functions/lib/transitous.js) for three Munich segments;
 * the expected values are the raw transitous fields those segments came from.
 */
class LiveSnapshotContractTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fixture = """
        {"v":1,"city":"muenchen","t":1791303272,"stale":false,
         "stops":[["Neubiberg",48.07569,11.66052],["Neuperlach Süd",48.0889,11.645],["Odeonsplatz",48.14338,11.57811],["Universität",48.15006,11.58105],["Erding, Gestütring Ärztezentr.",48.30808,11.91465],["Erding, Santnerstraße",48.3095,11.91672]],
         "lines":[["S5","METRO","00527f"],["U6","SUBWAY","0065ae"],["501","BUS","00355b"]],
         "segs":[[0,"20261006_17:53_de-DELFI_3432306470",0,1,-152,-32,60,1,"5vH9idQNqlgz"],
                 [1,"20261006_18:07_de-DELFI_3428091183",2,3,-152,-92,0,1,"vUVB9C8T6nHR"],
                 [2,"20261006_18:10_de-DELFI_3423597683",4,5,-152,-92,null,0,""]]}
    """.trimIndent()

    @Test
    fun readsTheServerFormat() {
        val segs = json.decodeFromString<LiveSnapshotDto>(fixture).toSegments()
        assertEquals(3, segs.size)

        val s5 = segs[0]
        assertEquals("20261006_17:53_de-DELFI_3432306470", s5.tripId)
        assertEquals("S5", s5.label)
        assertEquals("METRO", s5.mode)
        assertEquals("00527f", s5.colorHex)
        assertEquals("Neubiberg", s5.fromName)
        assertEquals("Neuperlach Süd", s5.toName)
        assertEquals(1791303120000L, s5.departureMs)
        assertEquals(1791303240000L, s5.arrivalMs)
        assertEquals(1, s5.delayMinutes)          // 60 s late
        assertTrue(s5.isRealtime)
        assertEquals("5vH9idQNqlgz", s5.shapeKey)
        assertEquals(48.07569, s5.fromLat, 1e-9)
        assertEquals(11.645, s5.toLon, 1e-9)

        assertEquals(0, segs[1].delayMinutes)

        // Not real-time and without geometry: no delay, empty key, straight line fallback.
        val bus = segs[2]
        assertNull(bus.delayMinutes)
        assertEquals("", bus.shapeKey)
        assertTrue(bus.hasStopCoordinates)
    }
}
