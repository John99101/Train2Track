package com.mvvtracker.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.mvvtracker.app.data.local.EventDao
import com.mvvtracker.app.data.local.FilterStore
import com.mvvtracker.app.data.local.SeenTrainsStore
import com.mvvtracker.app.data.remote.ServerFunctions
import com.mvvtracker.app.privacy.AdsConsent
import com.mvvtracker.app.privacy.AnalyticsSwitch
import com.mvvtracker.app.privacy.ConsentManager
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Delete my data" (Art. 17 GDPR), as promised in the app and on the data
 * deletion page:
 *  1. the deleteMyData Cloud Function erases everything on the server tied to this
 *     device's pseudonymous id (reports, delay votes, presence) and the
 *     anonymous account itself; the inspection statistics only lose the link;
 *  2. local data goes: this device's reports, seen trains, filters, every consent
 *     (usage statistics are switched off and their data reset, the advertising
 *     choice is forgotten so Google's dialog asks again at the next start);
 *  3. the app signs in with a fresh anonymous id, so nothing new links to the old.
 *
 * If the server step fails the old id is kept, so the user can simply retry
 * (with a new id the old data could no longer be found from the app).
 */
@Singleton
class AccountRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: ServerFunctions,
    private val eventDao: EventDao,
    private val seenTrains: SeenTrainsStore,
    private val filterStore: FilterStore,
    private val stationRecents: com.mvvtracker.app.data.station.StationRecents,
    private val consentManager: ConsentManager,
    private val analyticsSwitch: AnalyticsSwitch,
    private val adsConsent: AdsConsent
) {
    /** Returns true when the server side deletion succeeded. */
    suspend fun deleteAllMyData(): Boolean {
        val serverOk = try {
            if (auth.currentUser != null) functions.call("deleteMyData", timeoutSeconds = 120)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        eventDao.clearAll()
        seenTrains.clear()
        filterStore.clear()
        stationRecents.clear()
        // Every consent off (which switches Analytics off) and its data forgotten.
        consentManager.resetAllConsent()
        analyticsSwitch.reset()
        adsConsent.reset()
        // Signing out is enough: the app signs in with a fresh id by itself
        // (see MvvTrackerApp.ensureAnonymousSignIn).
        if (serverOk) auth.signOut()
        return serverOk
    }
}
