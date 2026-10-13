package com.mvvtracker.app.privacy

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.consentStore by preferencesDataStore(name = "consent_prefs")

/**
 * Central enforcement point for GDPR compliance (Regulation (EU) 2016/679).
 *
 * Design principles applied:
 *  - Lawful basis & consent (Art. 6/7): usage statistics and location are OFF
 *    by default and only enabled after an explicit, granular opt-in. Withdrawing
 *    consent is exactly as easy as giving it (Art. 7(3)). The choice about
 *    advertising is made in Google's consent dialog, see [AdsConsent].
 *  - Data minimization (Art. 5(1)(c)): we never collect name, email or
 *    precise device identifiers for ordinary use. A random, locally
 *    generated pseudonymous ID is used for user reports.
 *  - Purpose limitation (Art. 5(1)(b)): location is only ever used on the device,
 *    to (a) preselect the city and (b) show the position on the map.
 *  - Storage limitation (Art. 5(1)(e)): the device's own reports are purged after
 *    EventEntity.RETENTION_DAYS (see EventRepository.expireStaleReports).
 *  - Right to erasure / data portability (Art. 17/20): [resetAllConsent]
 *    plus [regeneratePseudonymousId] give the user a one-tap way to sever
 *    the link between past reports and their device, and the Settings
 *    screen exposes "Delete my data" which also calls the backend.
 *  - Data Protection by Design & Default (Art. 25): consent defaults to
 *    "off"; only IDs, not raw identifiers, are stored; everything below
 *    is a small, auditable surface rather than scattered flags.
 */
@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    private object Keys {
        val PSEUDONYMOUS_ID = stringPreferencesKey("pseudonymous_id")
        val HAS_SEEN_CONSENT_SCREEN = booleanPreferencesKey("has_seen_consent_screen")
        val HAS_SEEN_TUTORIAL = booleanPreferencesKey("has_seen_tutorial")
        val ANALYTICS_CONSENT = booleanPreferencesKey("analytics_consent")
        val PRECISE_LOCATION_CONSENT = booleanPreferencesKey("precise_location_consent")

        // Switches of earlier versions (push, personalised ads). No longer read;
        // only named here so that "Delete my data" removes a stored value.
        val OLD_PUSH_CONSENT = booleanPreferencesKey("push_consent")
        val OLD_AD_PERSONALIZATION_CONSENT = booleanPreferencesKey("ad_personalization_consent")
    }

    val hasSeenConsentScreen: Flow<Boolean> =
        appContext.consentStore.data.map { it[Keys.HAS_SEEN_CONSENT_SCREEN] ?: false }

    val hasSeenTutorial: Flow<Boolean> =
        appContext.consentStore.data.map { it[Keys.HAS_SEEN_TUTORIAL] ?: false }

    val analyticsConsent: Flow<Boolean> =
        appContext.consentStore.data.map { it[Keys.ANALYTICS_CONSENT] ?: false }

    val preciseLocationConsent: Flow<Boolean> =
        appContext.consentStore.data.map { it[Keys.PRECISE_LOCATION_CONSENT] ?: false }

    suspend fun pseudonymousId(): String {
        val existing = appContext.consentStore.data.map { it[Keys.PSEUDONYMOUS_ID] }.first()
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        appContext.consentStore.edit { it[Keys.PSEUDONYMOUS_ID] = generated }
        return generated
    }

    /** Right to erasure: sever any link between past activity and this device. */
    suspend fun regeneratePseudonymousId(): String {
        val fresh = UUID.randomUUID().toString()
        appContext.consentStore.edit { it[Keys.PSEUDONYMOUS_ID] = fresh }
        return fresh
    }

    suspend fun setConsentScreenSeen() {
        appContext.consentStore.edit { it[Keys.HAS_SEEN_CONSENT_SCREEN] = true }
    }

    suspend fun setTutorialSeen() {
        appContext.consentStore.edit { it[Keys.HAS_SEEN_TUTORIAL] = true }
    }

    suspend fun setAnalyticsConsent(granted: Boolean) {
        appContext.consentStore.edit { it[Keys.ANALYTICS_CONSENT] = granted }
    }

    suspend fun setPreciseLocationConsent(granted: Boolean) {
        appContext.consentStore.edit { it[Keys.PRECISE_LOCATION_CONSENT] = granted }
    }

    /** "Delete my data" - resets every local consent flag and rotates the ID. */
    suspend fun resetAllConsent() {
        appContext.consentStore.edit { prefs ->
            prefs[Keys.ANALYTICS_CONSENT] = false
            prefs[Keys.PRECISE_LOCATION_CONSENT] = false
            prefs.remove(Keys.OLD_PUSH_CONSENT)
            prefs.remove(Keys.OLD_AD_PERSONALIZATION_CONSENT)
        }
        regeneratePseudonymousId()
    }
}
