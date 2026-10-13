package com.mvvtracker.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAggregationTest {

    private val now = 1_000_000_000L
    private val trip = "T1"

    private fun entry(rider: String, at: Long, vararg flags: String) = ReportDoc(
        rider = rider, tripId = trip, state = "entered", line = "S1", station = "Marienplatz",
        createdAtMs = at, expiresAtMs = now + 60_000,
        throttled = "throttled" in flags, dismissed = "dismissed" in flags, verified = "verified" in flags
    )

    private fun exit(rider: String, at: Long, own: Boolean = false) = ReportDoc(
        rider = rider, tripId = trip, state = "exited", line = null, station = null,
        createdAtMs = at, expiresAtMs = now + 60_000, own = own
    )

    private fun flagged(vararg docs: ReportDoc) =
        ReportAggregation.aggregate(docs.toList(), now)[trip]!!.flagged

    @Test
    fun entriesCount() {
        assertEquals(2, flagged(entry("a", now - 5000), entry("b", now - 4000)))
    }

    @Test
    fun singleExitFromSomeoneElseDoesNotClear() {
        assertEquals(1, flagged(entry("a", now - 5000), exit("troll", now - 1000)))
    }

    @Test
    fun twoDifferentExitsClear() {
        assertEquals(0, flagged(entry("a", now - 5000), exit("b", now - 2000), exit("c", now - 1000)))
    }

    @Test
    fun reporterLeavingClearsTheirOwnReport() {
        // "a" entered, then flipped its own doc to exited: only the exit is left.
        assertEquals(0, flagged(exit("a", now - 1000)))
    }

    @Test
    fun exitsOlderThanANewEntryDoNotCount() {
        assertEquals(1, flagged(exit("b", now - 9000), exit("c", now - 8000), entry("a", now - 1000)))
    }

    @Test
    fun ownExitClearsOnlyForSelf() {
        assertEquals(0, flagged(entry("a", now - 5000), exit("me", now - 1000, own = true)))
        assertEquals(1, flagged(entry("a", now - 5000), exit("me", now - 1000, own = false)))
    }

    @Test
    fun dismissedThrottledAndExpiredDoNotCount() {
        val expired = entry("e", now - 99_000).copy(expiresAtMs = now - 1)
        val result = ReportAggregation.aggregate(
            listOf(entry("a", now - 5000, "dismissed"), entry("b", now - 4000, "throttled"), expired, entry("c", now - 3000)),
            now
        )[trip]!!
        assertEquals(1, result.flagged)
        assertEquals(1, result.dismissed)
        assertEquals(1, result.throttled)
    }

    @Test
    fun verifiedComesThrough() {
        assertTrue(ReportAggregation.aggregate(listOf(entry("a", now - 5000, "verified")), now)[trip]!!.verified)
    }
}
