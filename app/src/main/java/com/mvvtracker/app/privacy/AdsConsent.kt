package com.mvvtracker.app.privacy

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that decides whether the app may load advertising.
 *
 * The choice is made in Google's own consent dialog (User Messaging Platform, a
 * consent platform certified for the IAB Transparency and Consent Framework),
 * not in a switch of this app. Nothing is requested from AdMob and the ads
 * software is not started until the user has answered that dialog. The ads
 * software reads the stored choice by itself, so an ad request needs no extra
 * flags.
 *
 * Deliberately stricter than Google's own canRequestAds(): that is also true
 * when Google reports "no dialog required", which it does outside the EEA, UK
 * and Switzerland but also when no consent message is set up for the app in
 * AdMob. So without an answered dialog there are no ads at all.
 */
@Singleton
class AdsConsent @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adsStarted = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)
    /** True once the user answered Google's dialog (whatever the answer was). */
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    private val _hasPrivacyOptions = MutableStateFlow(false)
    /** True where the user must be able to reopen the dialog (Settings shows the entry). */
    val hasPrivacyOptions: StateFlow<Boolean> = _hasPrivacyOptions.asStateFlow()

    private val _choiceVersion = MutableStateFlow(0)
    /** Goes up whenever the user closed the dialog, so a shown ad is replaced. */
    val choiceVersion: StateFlow<Int> = _choiceVersion.asStateFlow()

    /**
     * Asks Google whether a choice is still needed and shows the dialog if so.
     * Called once the app's own first start screens are done, and on every start.
     */
    fun gather(activity: Activity) {
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    _choiceVersion.value++
                    refresh()
                }
            },
            { refresh() }
        )
        // A choice from an earlier start is valid right away.
        refresh()
    }

    /** Settings: lets the user change or withdraw the choice at any time. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            _choiceVersion.value++
            refresh()
        }
    }

    /** "Delete my data": forgets the choice; the dialog asks again at the next start. */
    fun reset() {
        info.reset()
        refresh()
    }

    private fun refresh() {
        val allowed = info.consentStatus == ConsentInformation.ConsentStatus.OBTAINED
        _canRequestAds.value = allowed
        _hasPrivacyOptions.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (allowed && adsStarted.compareAndSet(false, true)) {
            scope.launch { MobileAds.initialize(context) }
        }
    }
}
