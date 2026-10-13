package com.mvvtracker.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Devices with the app open in the last [windowMinutes] (server count, every 5 min). */
data class LiveStats(
    val activeUsers: Int,
    val android: Int,
    val ios: Int,
    val windowMinutes: Int,
    val updatedAtMs: Long?
)

/**
 * Active users: while the app is in the foreground it writes presence/{uid} =
 * { lastSeen, platform } every few minutes (nothing else; the server deletes it
 * after an hour of inactivity). The liveStats Cloud Function counts these into
 * stats/live, which every app may read, e.g. to show "N riders online".
 */
@Singleton
class PresenceRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    /** Heartbeat while the caller's coroutine runs (tie it to the foreground). */
    suspend fun heartbeatLoop() {
        while (true) {
            beat()
            delay(HEARTBEAT_MILLIS)
        }
    }

    private suspend fun beat() {
        val uid = auth.currentUser?.uid ?: return
        try {
            firestore.collection(COLLECTION).document(uid)
                .set(mapOf("lastSeen" to FieldValue.serverTimestamp(), "platform" to PLATFORM))
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or not deployed yet: the count is best effort.
        }
    }

    fun observeLiveStats(): Flow<LiveStats?> = callbackFlow {
        val registration = firestore.collection("stats").document("live")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null || !snap.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    LiveStats(
                        activeUsers = snap.getLong("activeUsers")?.toInt() ?: 0,
                        android = snap.getLong("android")?.toInt() ?: 0,
                        ios = snap.getLong("ios")?.toInt() ?: 0,
                        windowMinutes = snap.getLong("windowMinutes")?.toInt() ?: 10,
                        updatedAtMs = snap.getTimestamp("updatedAt")?.toDate()?.time
                    )
                )
            }
        awaitClose { registration.remove() }
    }

    private companion object {
        const val COLLECTION = "presence"
        const val PLATFORM = "android"
        const val HEARTBEAT_MILLIS = 4 * 60_000L   // the server counts the last 10 minutes
    }
}
