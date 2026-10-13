package com.mvvtracker.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app may ask our server, and when transitous directly. iOS mirrors these cases. */
class FallbackPacerTest {

    private val minute = 60_000L

    @Test
    fun `at the start everything is allowed`() {
        val pacer = FallbackPacer()
        assertTrue(pacer.serverAllowed(0))
        assertTrue(pacer.directAllowed("muenchen", 0))
    }

    @Test
    fun `a server error leaves the server alone for two minutes`() {
        val pacer = FallbackPacer()
        pacer.onServerFailure(nowMs = 1_000, deviceOffline = false)
        assertFalse(pacer.serverAllowed(1_000))
        assertFalse(pacer.serverAllowed(1_000 + 2 * minute - 1))
        assertTrue(pacer.serverAllowed(1_000 + 2 * minute))
    }

    @Test
    fun `a phone without connection does not back off from the server`() {
        val pacer = FallbackPacer()
        pacer.onServerFailure(nowMs = 1_000, deviceOffline = true)
        assertTrue(pacer.serverAllowed(1_001))
    }

    @Test
    fun `transitous is asked at most once per city in two minutes`() {
        val pacer = FallbackPacer()
        pacer.onDirectRequest("muenchen", 5_000)
        assertFalse(pacer.directAllowed("muenchen", 5_000))
        assertFalse(pacer.directAllowed("muenchen", 5_000 + 2 * minute - 1))
        assertTrue(pacer.directAllowed("muenchen", 5_000 + 2 * minute))
        // Another city has its own clock.
        assertTrue(pacer.directAllowed("berlin", 5_001))
    }

    @Test
    fun `with the server down for an hour one city makes about thirty direct requests, not 180`() {
        val pacer = FallbackPacer()
        var direct = 0
        var server = 0
        var now = 0L
        // The map refreshes every 20 s; the server fails every time it is asked.
        while (now < 60 * minute) {
            if (pacer.serverAllowed(now)) {
                server++
                pacer.onServerFailure(now, deviceOffline = false)
            }
            if (pacer.directAllowed("muenchen", now)) {
                pacer.onDirectRequest("muenchen", now)
                direct++
            }
            now += 20_000
        }
        assertEquals(30, direct)
        assertEquals(30, server)
    }

    @Test
    fun `offline phone tries the server at every refresh and transitous only every two minutes`() {
        val pacer = FallbackPacer()
        var direct = 0
        var server = 0
        var now = 0L
        while (now < 10 * minute) {
            if (pacer.serverAllowed(now)) {
                server++
                pacer.onServerFailure(now, deviceOffline = true)
            }
            if (pacer.directAllowed("muenchen", now)) {
                pacer.onDirectRequest("muenchen", now)
                direct++
            }
            now += 20_000
        }
        assertEquals(30, server)   // every 20 s: back online within one refresh
        assertEquals(5, direct)
    }
}
