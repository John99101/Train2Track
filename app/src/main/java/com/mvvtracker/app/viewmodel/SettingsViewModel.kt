package com.mvvtracker.app.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.mvvtracker.app.privacy.AdsConsent
import com.mvvtracker.app.privacy.ConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val consentManager: ConsentManager,
    private val adsConsent: AdsConsent,
    private val auth: FirebaseAuth
) : ViewModel() {

    /**
     * The random identifier of this installation (privacy policy, section 4), or
     * null while the app is not signed in. Shown in the settings so that a user
     * can name it in a request by mail; it changes with "Delete my data".
     */
    val randomId: StateFlow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), auth.currentUser?.uid)

    val analyticsConsent: Flow<Boolean> = consentManager.analyticsConsent
    val preciseLocationConsent: Flow<Boolean> = consentManager.preciseLocationConsent

    /** Advertising follows Google's consent dialog, see [AdsConsent]. */
    val canRequestAds: StateFlow<Boolean> = adsConsent.canRequestAds
    val adChoiceVersion: StateFlow<Int> = adsConsent.choiceVersion
    val hasAdPrivacyOptions: StateFlow<Boolean> = adsConsent.hasPrivacyOptions

    fun setAnalyticsConsent(v: Boolean) = viewModelScope.launch { consentManager.setAnalyticsConsent(v) }
    fun setPreciseLocationConsent(v: Boolean) = viewModelScope.launch { consentManager.setPreciseLocationConsent(v) }

    /** Reopens Google's dialog so the advertising choice can be changed or withdrawn. */
    fun showAdPrivacyOptions(activity: Activity) = adsConsent.showPrivacyOptions(activity)
}
