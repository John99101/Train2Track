package com.mvvtracker.app

import android.app.Application
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.mvvtracker.app.privacy.AnalyticsSwitch
import com.mvvtracker.app.util.AppInfo
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Per GDPR (Art. 6, 7 GDPR - lawful basis & consent), any non-essential data
 * collection only starts after the user agreed: Analytics is disabled in the
 * manifest and only switched on by [AnalyticsSwitch]; advertising is only loaded
 * once the user answered Google's consent dialog (see
 * [com.mvvtracker.app.privacy.AdsConsent], started from MainActivity).
 */
@HiltAndroidApp
class MvvTrackerApp : Application() {

    @Inject lateinit var analyticsSwitch: AnalyticsSwitch

    override fun onCreate() {
        super.onCreate()
        // Analytics stays off unless the user agreed (and follows the switch from here on).
        analyticsSwitch.start()
        initAppCheck()
        configureOsmdroid()
        removeLeftoversOfEarlierVersions()
        ensureAnonymousSignIn()
    }

    /**
     * Firebase App Check: attests that Firestore/Auth calls come from the genuine
     * app binary, which blocks scripted/bot abuse of the reports backend. The
     * concrete provider differs per build type (Play Integrity in release, a debug
     * provider in debug) and is supplied by [installAppCheck], which has a separate
     * implementation in src/release and src/debug - the debug provider artifact is
     * only on the debug classpath, so it can't be referenced from shared code.
     * Whether the servers refuse requests without it is set per product in the
     * Firebase console.
     */
    private fun initAppCheck() {
        installAppCheck()
    }

    /**
     * Signs the device in anonymously so reports have a stable (pseudonymous)
     * identity for dedup/rate-limiting - no name or email required (GDPR).
     */
    private fun ensureAnonymousSignIn() {
        val auth = FirebaseAuth.getInstance()
        // The listener fires right away with the current state and again whenever the
        // id goes away ("delete my data", or the server removed an id that was unused
        // for a long time), so the app always gets a fresh id by itself.
        auth.addAuthStateListener { signIn(it) }
        // A first start without internet: sign in as soon as a connection is there.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    retryDelayMs = FIRST_RETRY_MS
                    signIn(auth)
                }
            }
        )
    }

    /** Signs in when there is no id; when that fails, tries again a little later. */
    private fun signIn(auth: FirebaseAuth) {
        if (auth.currentUser != null || !signingIn.compareAndSet(false, true)) return
        auth.signInAnonymously().addOnCompleteListener { task ->
            signingIn.set(false)
            if (task.isSuccessful) {
                retryDelayMs = FIRST_RETRY_MS
            } else {
                // No connection or the server was busy: again after 15 s, then less often.
                retryHandler.postDelayed({ signIn(auth) }, retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private val signingIn = java.util.concurrent.atomic.AtomicBoolean(false)
    private val retryHandler = Handler(Looper.getMainLooper())
    @Volatile private var retryDelayMs = FIRST_RETRY_MS

    /**
     * OpenStreetMap tile servers reject the default HTTP user-agent and ask apps
     * to name themselves with a contact, so we send the app's own one, and we pin
     * the tile cache to app-private storage (works without any storage permission
     * on modern Android).
     */
    private fun configureOsmdroid() {
        val cfg = org.osmdroid.config.Configuration.getInstance()
        cfg.userAgentValue = AppInfo.USER_AGENT
        cfg.osmdroidBasePath = File(cacheDir, "osmdroid")
        cfg.osmdroidTileCache = File(cfg.osmdroidBasePath, "tiles")
    }

    /**
     * Tidies up on phones that update from an earlier version:
     *  - the job that woke the app every 15 minutes (the app has no background
     *    job any more);
     *  - the notification channels (the app sends no notifications any more, so
     *    they should not stay listed in the system settings);
     *  - the notification token and registration data Google's messaging
     *    software kept on the phone (that software is no longer in the app).
     */
    private fun removeLeftoversOfEarlierVersions() {
        runCatching { WorkManager.getInstance(this).cancelUniqueWork(OLD_BACKGROUND_JOB) }
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            OLD_NOTIFICATION_CHANNELS.forEach(manager::deleteNotificationChannel)
        }
        OLD_MESSAGING_STORES.forEach { runCatching { deleteSharedPreferences(it) } }
    }

    private companion object {
        const val OLD_BACKGROUND_JOB = "feed_poll_worker"
        val OLD_NOTIFICATION_CHANNELS = listOf("channel_disruptions", "channel_resolved", "channel_general")
        val OLD_MESSAGING_STORES = listOf("com.google.firebase.messaging", "com.google.android.gms.appid")
        const val FIRST_RETRY_MS = 15_000L
        const val MAX_RETRY_MS = 5 * 60_000L
    }
}
