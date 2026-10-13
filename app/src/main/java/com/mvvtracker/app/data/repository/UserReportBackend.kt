package com.mvvtracker.app.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.mvvtracker.app.data.model.SBahnEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's own backend, on Firebase. It:
 *  - writes each rider's train report to the `reports` Firestore collection so
 *    other devices see it (this is what makes the "!" cross-device). A report
 *    does not show who wrote it, see [ReportIds];
 *  - streams the currently-active reports back, aggregated per train, but only
 *    for the cities the app is showing ([ReportScope]): every report carries the
 *    city its train was seen in.
 *
 * Reports need only anonymous Auth + Firestore (both free). Spam limits and
 * moderation flags come from Cloud Functions; without them reports still work.
 * Admin actions (push, moderation) live in [AdminBackend].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UserReportBackend @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val reportScope: ReportScope
) {
    /**
     * Writes this rider's entry on a train to [collection] (a report or a delay
     * vote) together with its owner note. There is one entry per rider per train,
     * under a name that does not show the rider (see [ReportIds]), so writing
     * again overwrites.
     *
     * It is sent as a transaction: it either reaches the server now or fails.
     * A plain write would be kept on the phone while offline and sent whenever
     * the connection returns, when the report may no longer be true.
     */
    private suspend fun writeEntry(collection: String, tripId: String, ttlMillis: Long, fields: Map<String, Any>) {
        val uid = uid()
        val docId = ReportIds.hiddenDocId(uid, tripId)
        val expiresAt = Timestamp(Date(System.currentTimeMillis() + ttlMillis))
        val entry = fields + mapOf(
            "tripId" to tripId,
            "uid" to docId,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to expiresAt
        )
        val note = mapOf(
            "uid" to uid,
            "collection" to collection,
            "doc" to docId,
            "tripId" to tripId,
            "expiresAt" to expiresAt
        )
        val entryRef = firestore.collection(collection).document(docId)
        val noteRef = firestore.collection(COLLECTION_OWNERS).document(ReportIds.ownerNoteId(collection, docId))
        withTimeoutOrNull(SEND_TIMEOUT_MILLIS) {
            firestore.runTransaction { tx ->
                tx.set(entryRef, entry)
                tx.set(noteRef, note)
                true
            }.await()
        } ?: throw IOException("Sending timed out.")
    }

    /** Ensures an anonymous identity exists and returns its uid. */
    private suspend fun uid(): String =
        (auth.currentUser ?: auth.signInAnonymously().await().user)?.uid
            ?: error("Could not obtain an anonymous Firebase user")

    /**
     * Writes a trip-specific report: one active report per rider per train, so
     * reporting the same train again overwrites. Throws when it could not be sent.
     */
    suspend fun submitReport(event: SBahnEvent, city: String) {
        val tripId = event.tripId ?: return // only train-specific reports sync
        writeEntry(
            COLLECTION_REPORTS, tripId, REPORT_TTL_MILLIS,
            mapOf(
                "localId" to event.id,
                "line" to event.lines.joinToString(","),
                "station" to (event.stationsAffected.firstOrNull() ?: ""),
                "description" to event.description,
                "state" to STATE_ENTERED,
                "city" to city
            )
        )
        logControlEvent(event, city)
    }

    /**
     * Persistent analytics log of ticket-control events (does NOT expire, unlike
     * the live [reports]). Written on every "Entered" so we can later analyse which
     * trains are controlled how often, when, and where. It carries no id: the
     * statistics need no rider. Best-effort: never blocks the actual report. Only
     * admins/server can read it (see firestore.rules); the whole collection can
     * be wiped for testing via functions/clearAnalytics.js.
     */
    private suspend fun logControlEvent(event: SBahnEvent, city: String) {
        runCatching {
            firestore.collection(COLLECTION_CONTROL_LOG).add(
                mapOf(
                    "tripId" to (event.tripId ?: ""),
                    "line" to event.lines.joinToString(","),
                    "station" to (event.stationsAffected.firstOrNull() ?: ""),
                    "city" to city,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    /**
     * "Exited": records an EXIT vote for the train. ANY user viewing the train can
     * report that the control has left. It overwrites this user's own doc for the
     * trip, so a rider who entered and then exits flips their own vote; other
     * riders' exits clear the "!" once two of them agree (see [ReportAggregation]).
     * Throws when it could not be sent.
     */
    suspend fun endReport(tripId: String, city: String) =
        writeEntry(
            COLLECTION_REPORTS, tripId, REPORT_TTL_MILLIS,
            mapOf("state" to STATE_EXITED, "city" to city)
        )

    /**
     * The signed in id, emitted again whenever it changes (first sign in, "delete
     * my data", an id the server removed after a long break). The listeners below
     * restart with it: a listener started before sign in would be refused for good.
     */
    private val uidFlow: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    /**
     * The live (not yet expired) documents of [collection], for [cities] only, or
     * the whole collection when [cities] is null. If the city query is refused
     * (its index is not deployed yet) it falls back to the whole collection, as
     * older app versions load it. Empty on any other error.
     */
    private fun liveDocs(collection: String, cities: Set<String>?): Flow<List<DocumentSnapshot>> = callbackFlow {
        var registration: ListenerRegistration? = null
        fun listen(byCity: Boolean) {
            var query: Query = firestore.collection(collection)
            if (byCity && cities != null) query = query.whereIn("city", cities.toList())
            registration = query
                .whereGreaterThan("expiresAt", Timestamp.now())
                .addSnapshotListener { snapshot, error ->
                    val refused = (error as? FirebaseFirestoreException)?.code ==
                        FirebaseFirestoreException.Code.PERMISSION_DENIED
                    if (error != null && byCity && cities != null && !refused) {
                        registration?.remove()
                        listen(byCity = false)
                    } else {
                        trySend(if (error != null || snapshot == null) emptyList() else snapshot.documents)
                    }
                }
        }
        listen(byCity = true)
        awaitClose { registration?.remove() }
    }

    /** [liveDocs] for the cities on screen, restarted when they or the id change. */
    private fun docsInScope(collection: String): Flow<List<DocumentSnapshot>> =
        combine(uidFlow, reportScope.cities) { uid, cities -> uid to cities }
            .distinctUntilChanged()
            .flatMapLatest { (uid, cities) ->
                if (uid == null || cities.isEmpty()) flowOf(emptyList()) else liveDocs(collection, cities)
            }

    /**
     * Live report state per train (see [ReportAggregation] for the rules: spam
     * limit, admin moderation, two independent "has left" votes to clear), for the
     * cities on screen. Also re-evaluated every minute so expired reports disappear
     * even when nothing else changes. Empty on any error so the UI degrades gracefully.
     */
    fun observeReportedTrips(): Flow<Map<String, TripReports>> =
        aggregated(docsInScope(COLLECTION_REPORTS))

    /** The same for every city at once: only for the admin console. */
    fun observeAllReportedTrips(): Flow<Map<String, TripReports>> =
        aggregated(uidFlow.flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else liveDocs(COLLECTION_REPORTS, cities = null)
        })

    private fun aggregated(docs: Flow<List<DocumentSnapshot>>): Flow<Map<String, TripReports>> = channelFlow {
        var latest: List<ReportDoc> = emptyList()
        fun emit() = trySend(ReportAggregation.aggregate(latest, System.currentTimeMillis()))
        launch {
            docs.collect { snapshot ->
                val selfUid = auth.currentUser?.uid
                latest = snapshot.mapNotNull { it.toReportDoc(selfUid) }
                emit()
            }
        }
        launch {
            while (true) {
                delay(REEVALUATE_MILLIS)
                emit()
            }
        }
    }

    private fun DocumentSnapshot.toReportDoc(selfUid: String?): ReportDoc? {
        val tripId = getString("tripId") ?: return null
        // Normally the hidden name of the entry. An entry of an older app version
        // still holds that rider's id until the server has renamed it.
        val rider = getString("uid")
        return ReportDoc(
            rider = rider ?: id,
            tripId = tripId,
            state = getString("state"),
            line = getString("line"),
            station = getString("station"),
            // A just-written report has no server time yet: use the local estimate.
            createdAtMs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time,
            expiresAtMs = getTimestamp("expiresAt")?.toDate()?.time,
            throttled = getBoolean("throttled") == true,
            dismissed = getBoolean("dismissed") == true,
            verified = getBoolean("verified") == true,
            own = selfUid != null && (id == ReportIds.hiddenDocId(selfUid, tripId) || rider == selfUid)
        )
    }

    /**
     * Crowd-sourced DELAY vote (Phase 6 / Option 2) — kept in a SEPARATE
     * `delay_reports` collection so it can never influence the inspector "!"
     * badge. One vote per rider per train, overwritten on re-vote. [vote] is
     * [DELAY_CONFIRM] ("yes, it's late") or [DELAY_ON_TIME] ("no, it's on time").
     * Expires quickly — delays are ephemeral. Throws when it could not be sent.
     */
    suspend fun submitDelayVote(tripId: String, vote: String, city: String) =
        writeEntry(
            COLLECTION_DELAY_VOTES, tripId, DELAY_VOTE_TTL_MILLIS,
            mapOf("vote" to vote, "city" to city)
        )

    /**
     * Live, per-train tally of fresh delay votes: tripId -> (confirm, onTime)
     * counts, for the cities on screen. Lets the UI show crowd delay confidence.
     * Empty on any error.
     */
    fun observeDelayVotes(): Flow<Map<String, Pair<Int, Int>>> =
        docsInScope(COLLECTION_DELAY_VOTES).map { docs ->
            docs
                // Votes over the spam limit (flagged by the server) do not count.
                .filter { it.getString("tripId") != null && it.getBoolean("throttled") != true }
                .groupBy { it.getString("tripId")!! }
                .mapValues { (_, votes) ->
                    val confirm = votes.count { it.getString("vote") == DELAY_CONFIRM }
                    val onTime = votes.count { it.getString("vote") == DELAY_ON_TIME }
                    confirm to onTime
                }
        }

    /**
     * Live nationwide disruptions from the `disruptions` collection (written
     * server-side by the refreshDisruptions Cloud Function). Only active alerts,
     * most-severe first, capped. A few KB per device — never the 35 MB feed.
     */
    fun observeDisruptions(): Flow<List<com.mvvtracker.app.data.model.Disruption>> =
        uidFlow.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else liveDisruptions() }

    private fun liveDisruptions(): Flow<List<com.mvvtracker.app.data.model.Disruption>> = callbackFlow {
        val registration = firestore.collection(COLLECTION_DISRUPTIONS)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot.documents.mapNotNull { doc ->
                    val title = doc.getString("title") ?: return@mapNotNull null
                    val end = doc.getLong("endEpochMillis")
                    if (end != null && end < now) return@mapNotNull null
                    com.mvvtracker.app.data.model.Disruption(
                        id = doc.id,
                        title = title,
                        description = doc.getString("description") ?: "",
                        affected = (doc.get("affected") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        startEpochMillis = doc.getLong("startEpochMillis") ?: now,
                        endEpochMillis = end,
                        severe = doc.getBoolean("severe") ?: false
                    )
                }.sortedByDescending { it.severe }
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    companion object {
        const val COLLECTION_REPORTS = "reports"
        const val COLLECTION_CONTROL_LOG = "control_log"
        const val COLLECTION_DELAY_VOTES = "delay_reports"
        const val COLLECTION_DISRUPTIONS = "disruptions"
        const val COLLECTION_OWNERS = "doc_owners"
        // After this long without an answer from the server, sending counts as failed.
        const val SEND_TIMEOUT_MILLIS = 15_000L
        const val FUNCTIONS_REGION = "europe-west3"
        const val REPORT_TTL_MILLIS = 90L * 60 * 1000
        const val DELAY_VOTE_TTL_MILLIS = 30L * 60 * 1000   // delays are ephemeral
        const val REEVALUATE_MILLIS = 60_000L
        const val STATE_ENTERED = "entered"
        const val STATE_EXITED = "exited"
        const val DELAY_CONFIRM = "confirm"   // "yes, it's late"
        const val DELAY_ON_TIME = "on_time"   // "no, it's on time"
    }
}
