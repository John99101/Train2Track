package com.mvvtracker.app.privacy

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that turns Google Analytics for Firebase on or off.
 *
 * Analytics is OFF by default: the manifest disables collection
 * (firebase_analytics_collection_enabled = false, all consent types denied), so
 * nothing is collected before the user decides. It is only switched on while the
 * "Usage statistics" consent is given, and off again the moment it is withdrawn.
 * The usage statistics are never used for advertising: the advertising consent
 * types stay denied, whatever the user chose in Google's ad consent dialog.
 */
@Singleton
class AnalyticsSwitch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consentManager: ConsentManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Call once at app start: applies the saved choice and follows every change. */
    fun start() {
        scope.launch {
            consentManager.analyticsConsent.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(analytics: Boolean) {
        val firebase = FirebaseAnalytics.getInstance(context)
        firebase.setConsent(
            mapOf(
                ConsentType.ANALYTICS_STORAGE to if (analytics) ConsentStatus.GRANTED else ConsentStatus.DENIED,
                ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED
            )
        )
        firebase.setAnalyticsCollectionEnabled(analytics)
    }

    /** "Delete my data": forgets the Analytics app instance id and anything not yet sent. */
    fun reset() {
        FirebaseAnalytics.getInstance(context).resetAnalyticsData()
    }
}
