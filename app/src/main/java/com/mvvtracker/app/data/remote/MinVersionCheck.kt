package com.mvvtracker.app.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.mvvtracker.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The oldest build that may still be used. The owner sets it in the Firebase
 * console (Firestore, document config/app, number field androidMinBuild) when a
 * released version has a serious fault; that version then asks for an update.
 * No document, no field or no connection: nothing is blocked. iOS reads
 * iosMinBuild from the same document.
 */
@Singleton
class MinVersionCheck @Inject constructor(private val firestore: FirebaseFirestore) {

    /** True when this build is older than the oldest build that may still be used. */
    suspend fun updateRequired(): Boolean = try {
        val min = firestore.collection(COLLECTION).document(DOCUMENT).get().await().getLong(FIELD) ?: 0L
        BuildConfig.VERSION_CODE < min
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    companion object {
        const val COLLECTION = "config"
        const val DOCUMENT = "app"
        const val FIELD = "androidMinBuild"
    }
}
