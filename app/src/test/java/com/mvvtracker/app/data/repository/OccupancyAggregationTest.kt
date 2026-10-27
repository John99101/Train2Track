package com.mvvtracker.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OccupancyAggregationTest {
    private val now = 1_000_000L
    private fun vote(trip: String, level: Int, expiresIn: Long = 60_000L, throttled: Boolean = false, own: Boolean = false) =
        OccupancyVote(trip, level, now + expiresIn, throttled, own)

    @Test
    fun oneRiderIsShownAtOnce() {
        val result = OccupancyAggregation.aggregate(listOf(vote("t1", 3, own = true)), now)
        assertEquals(3, result.getValue("t1").level)
        assertEquals(1, result.getValue("t1").votes)
        assertEquals(3, result.getValue("t1").ownLevel)
    }

    @Test
    fun everyVoteMovesTheAverage() {
        // 3 and 4 show 4; a third rider with 3 brings it back to 3.
        assertEquals(4, OccupancyAggregation.aggregate(listOf(vote("t1", 3), vote("t1", 4)), now).getValue("t1").level)
        assertEquals(3, OccupancyAggregation.aggregate(listOf(vote("t1", 3), vote("t1", 4), vote("t1", 3)), now).getValue("t1").level)
        assertEquals(4, OccupancyAggregation.aggregate(listOf(vote("t1", 1), vote("t1", 5), vote("t1", 5)), now).getValue("t1").level)
    }

    @Test
    fun expiredThrottledAndInvalidVotesDoNotCount() {
        val votes = listOf(
            vote("t1", 5),
            vote("t1", 1, expiresIn = -1),          // older than 20 minutes
            vote("t1", 1, throttled = true),        // over the spam limit
            vote("t1", 9),                          // not a level
            vote("t2", 1), vote("t2", 2)
        )
        val result = OccupancyAggregation.aggregate(votes, now)
        assertEquals(1, result.getValue("t1").votes)
        assertEquals(5, result.getValue("t1").level)
        assertNull(result.getValue("t1").ownLevel)
        assertEquals(2, result.getValue("t2").level)   // 1.5 rounds up
    }

    @Test
    fun averageRoundsToTheNearestLevel() {
        assertEquals(3, OccupancyAggregation.average(listOf(5, 1, 3)))
        assertEquals(4, OccupancyAggregation.average(listOf(2, 5)))        // 3.5
        assertEquals(2, OccupancyAggregation.average(listOf(1, 1, 1, 5)))  // 2.0
        assertEquals(2, OccupancyAggregation.average(listOf(1, 2, 2, 2)))  // 1.75
        assertTrue(OccupancyAggregation.aggregate(emptyList(), now).isEmpty())
    }
}
