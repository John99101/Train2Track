package com.mvvtracker.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMotionTest {

    private fun hop(trip: String?, from: String, to: String, depS: Long, arrS: Long, mode: String = "METRO") =
        LiveSegment(
            tripId = trip, label = "S1", mode = mode, colorHex = null,
            fromName = from, toName = to,
            fromLat = 48.0, fromLon = 11.0, toLat = 48.1, toLon = 11.1,
            departureMs = depS * 1000, arrivalMs = arrS * 1000,
            delayMinutes = null, isRealtime = true, shapeKey = ""
        )

    @Test
    fun aVehicleStandsAtTheStopBetweenItsHops() {
        // Arrives at B at 60 s, leaves B at 120 s: shown at B from 60 s on.
        val out = LiveMotion.withStandingTimes(listOf(hop("t", "B", "C", 120, 180), hop("t", "A", "B", 0, 60)))
        assertEquals(60_000L, out[0].standFromMs)
        // Its first hop in the data: a minute before it leaves.
        assertEquals(-60_000L, out[1].standFromMs)
    }

    @Test
    fun standingIsCutAfterFiveMinutes() {
        val out = LiveMotion.withStandingTimes(listOf(hop("t", "A", "B", 0, 60), hop("t", "B", "C", 1260, 1320)))
        assertEquals(1260_000L - LiveMotion.MAX_STAND_MS, out[1].standFromMs)
    }

    @Test
    fun noStandingWhenHopsOverlapOrDoNotMeetOrHaveNoJourney() {
        val overlap = LiveMotion.withStandingTimes(listOf(hop("t", "A", "B", 0, 60), hop("t", "A", "B", 0, 60)))
        assertEquals(0L, overlap[1].standFromMs)
        val apart = LiveMotion.withStandingTimes(listOf(hop("t", "A", "B", 0, 60), hop("t", "X", "Y", 120, 180)).map {
            if (it.fromName == "X") it.copy(fromLat = 50.0, fromLon = 9.0) else it
        })
        assertEquals(120_000L, apart[1].standFromMs)
        val noTrip = LiveMotion.withStandingTimes(listOf(hop(null, "A", "B", 0, 60)))
        assertEquals(0L, noTrip[0].standFromMs)
    }

    @Test
    fun aLongStopKeepsItsStandingTimeAfterTheHopBeforeLeftTheData() {
        val memory = LiveMotion.StandMemory()
        // First download: arrives at B at 60 s, leaves B at 300 s.
        val first = LiveMotion.withStandingTimes(
            listOf(hop("t", "A", "B", 0, 60), hop("t", "B", "C", 300, 360)), memory, nowMs = 100_000
        )
        assertEquals(60_000L, first[1].standFromMs)
        // Next download: the hop A to B is gone, the vehicle still stands at B from 60 s.
        val next = LiveMotion.withStandingTimes(listOf(hop("t", "B", "C", 300, 360)), memory, nowMs = 200_000)
        assertEquals(60_000L, next[0].standFromMs)
        // Also when the departure is a minute late.
        val late = LiveMotion.withStandingTimes(listOf(hop("t", "B", "C", 360, 420)), memory, nowMs = 210_000)
        assertEquals(60_000L, late[0].standFromMs)
        // Without the memory it would only be shown a minute before it leaves.
        assertEquals(240_000L, LiveMotion.withStandingTimes(listOf(hop("t", "B", "C", 300, 360)))[0].standFromMs)
        // A second visit of the same stop much later (a loop line) starts fresh.
        val again = LiveMotion.withStandingTimes(listOf(hop("t", "B", "C", 1500, 1560)), memory, nowMs = 1_400_000)
        assertEquals(1_440_000L, again[0].standFromMs)
    }

    @Test
    fun aTrainStartsSlowRunsSteadyAndArrivesSlow() {
        val d = 120_000L
        assertEquals(0.0, LiveMotion.distanceFraction(-5_000, d, "METRO"), 1e-9)
        assertEquals(1.0, LiveMotion.distanceFraction(d, d, "METRO"), 1e-9)
        assertEquals(0.5, LiveMotion.distanceFraction(d / 2, d, "METRO"), 1e-9)
        // Behind constant speed right after leaving, ahead of it right before arriving.
        assertTrue(LiveMotion.distanceFraction(10_000, d, "METRO") < 10.0 / 120)
        assertTrue(LiveMotion.distanceFraction(110_000, d, "METRO") > 110.0 / 120)
        // Always moving forward.
        var last = -1.0
        for (t in 0..120) {
            val f = LiveMotion.distanceFraction(t * 1000L, d, "BUS")
            assertTrue(f >= last)
            last = f
        }
    }
}
