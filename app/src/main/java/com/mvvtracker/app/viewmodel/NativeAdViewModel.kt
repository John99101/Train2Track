package com.mvvtracker.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.mvvtracker.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The advert of the Feed. It stays while the user switches between the tabs:
 * before, every return to the Feed asked Google for a new advert, which cost data
 * and made the switch slower. A new one is loaded only when there is none yet, when
 * the ad choice changed, when the last one is an hour old, or one minute after the
 * user closed it, and only while the Feed is on screen. Nothing is requested
 * before the user answered Google's consent dialog (see privacy/AdsConsent).
 */
@HiltViewModel
class NativeAdViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _ad = MutableStateFlow<NativeAd?>(null)
    val ad: StateFlow<NativeAd?> = _ad.asStateFlow()

    private val _closed = MutableStateFlow(false)
    /** The user closed the advert: the slot stays empty until [nextLoadAtMs]. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    /** When the current advert was first on screen (it can be closed 15 s later); 0 = not yet. */
    var shownSinceMs = 0L
        private set

    /** After a close or a failed request: no new request before this time. */
    var nextLoadAtMs = 0L
        private set

    private var loadedAtMs = 0L
    private var loadedForChoice = -1
    private var loading = false
    private var cleared = false

    /** Called while the slot is on screen: loads an advert when one is due. */
    fun showing(canRequestAds: Boolean, choiceVersion: Int) {
        if (!canRequestAds) {
            drop()
            return
        }
        val now = System.currentTimeMillis()
        if (now < nextLoadAtMs || loading) return
        _closed.value = false
        val due = _ad.value == null || loadedForChoice != choiceVersion || now - loadedAtMs > MAX_AGE_MS
        if (!due) return
        drop()
        loading = true
        AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_UNIT)
            .forNativeAd { ad ->
                loading = false
                if (cleared) {
                    ad.destroy()
                    return@forNativeAd
                }
                _ad.value = ad
                loadedAtMs = System.currentTimeMillis()
                loadedForChoice = choiceVersion
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Leave the slot empty, and do not ask again right away.
                    loading = false
                    nextLoadAtMs = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    /** The current advert is on screen. */
    fun markShown() {
        if (shownSinceMs == 0L) shownSinceMs = System.currentTimeMillis()
    }

    /** The user closed the advert: a new one pops up one minute later. */
    fun close() {
        drop()
        _closed.value = true
        nextLoadAtMs = System.currentTimeMillis() + RELOAD_AFTER_CLOSE_MS
    }

    private fun drop() {
        _ad.value?.destroy()
        _ad.value = null
        shownSinceMs = 0L
    }

    override fun onCleared() {
        cleared = true
        drop()
    }

    private companion object {
        // After a close, a fresh advert pops up one minute later (the owner's choice).
        const val RELOAD_AFTER_CLOSE_MS = 60_000L
        const val RETRY_AFTER_FAILURE_MS = 60_000L
        // Google advises not to show a native advert loaded more than an hour ago.
        const val MAX_AGE_MS = 60 * 60_000L
    }
}
