package com.mvvtracker.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReportIdsTest {

    @Test
    fun tripKeyReplacesEverythingADocumentNameMayNotContain() {
        assertEquals("20261013_08-15_de-DELFI_1-2", ReportIds.tripKey("20261013_08:15_de-DELFI_1|2"))
    }

    /** The same value as functions/lib/reportIds.js and ReportKey.swift give. */
    @Test
    fun hiddenDocIdMatchesTheServer() {
        assertEquals(
            "2ae1a21bc6b6cf62b2a4688f169b6a3330e220fcc9ca8aab17797b0436b479bf",
            ReportIds.hiddenDocId("abc", "de:1|2")
        )
    }

    @Test
    fun hiddenDocIdDiffersPerTripAndPerDevice() {
        assertNotEquals(ReportIds.hiddenDocId("abc", "trip1"), ReportIds.hiddenDocId("abc", "trip2"))
        assertNotEquals(ReportIds.hiddenDocId("abc", "trip1"), ReportIds.hiddenDocId("abd", "trip1"))
    }

    @Test
    fun ownerNoteId() {
        assertEquals("reports__ff00", ReportIds.ownerNoteId("reports", "ff00"))
    }
}
