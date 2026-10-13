package com.mvvtracker.app.data.repository

import java.security.MessageDigest

/**
 * The names of this device's report and delay vote documents.
 *
 * Every app that shows a train loads the reports on it, so a report must not show
 * who wrote it. Its name is therefore a hash of the device's random id and the
 * trip: different on every train, so two entries of one device cannot be linked,
 * and impossible to turn back into the id. The entry's `uid` field holds the same
 * hash, never the id.
 *
 * The id itself goes into an owner note in a collection no app can read. The
 * server needs it for the spam limit, moderation and "delete my data".
 *
 * The server (functions/lib/reportIds.js), the rules (firestore.rules) and iOS
 * (ReportKey.swift) build exactly the same names.
 */
object ReportIds {

    private val UNSAFE = Regex("[^A-Za-z0-9_-]")

    /** A trip id made safe for a document name. */
    fun tripKey(tripId: String): String = tripId.replace(UNSAFE, "-")

    /** The name of the device's entry on a train: SHA-256 of "<uid>__<trip key>", lowercase hex. */
    fun hiddenDocId(uid: String, tripId: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${uid}__${tripKey(tripId)}".toByteArray(Charsets.UTF_8))
        val out = CharArray(hash.size * 2)
        hash.forEachIndexed { i, b ->
            out[2 * i] = HEX[(b.toInt() shr 4) and 0xF]
            out[2 * i + 1] = HEX[b.toInt() and 0xF]
        }
        return String(out)
    }

    private const val HEX = "0123456789abcdef"

    /** The name of the owner note for the entry [docId] of [collection]. */
    fun ownerNoteId(collection: String, docId: String): String = "${collection}__$docId"
}
