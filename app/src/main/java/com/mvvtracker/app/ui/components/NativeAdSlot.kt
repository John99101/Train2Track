package com.mvvtracker.app.ui.components

import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.withStarted
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.mvvtracker.app.BuildConfig
import com.mvvtracker.app.R
import com.mvvtracker.app.viewmodel.SettingsViewModel

private const val SKIPPABLE_AFTER_MS = 5_000
// After a dismiss, wait a random 2–4 minutes before bringing a fresh ad back
// (so it doesn't vanish for the session, without feeling spammy).
private const val RELOAD_MIN_MS = 120_000L
private const val RELOAD_MAX_MS = 240_000L

/**
 * A real AdMob **native** ad rendered inside our own card, dismissible with an
 * "X" that appears after 5 seconds (a countdown ring shows until then). Native
 * format is used precisely because it lets us own the layout + close control.
 *
 * Self-managing: after the user dismisses it, the slot hides and then loads a
 * fresh ad once the reload cooldown passes — instead of disappearing for good.
 * Renders nothing until an ad loads (and nothing on failure), so it never blocks
 * the UI. Uses a TEST ad unit in debug and the real unit in release
 * (BuildConfig.ADMOB_NATIVE_UNIT).
 *
 * Nothing is requested before the user answered Google's consent dialog (see
 * privacy/AdsConsent). The ads software reads that choice by itself, so the
 * request carries no flags of its own.
 */
@Composable
fun NativeAdSlot(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }   // bump to load a fresh ad
    var hidden by remember { mutableStateOf(false) }     // temporarily hidden after a dismiss

    val canRequestAds by settingsViewModel.canRequestAds.collectAsState()
    val choiceVersion by settingsViewModel.adChoiceVersion.collectAsState()

    // Load a fresh ad on first show, whenever the ad choice changes, and after each
    // reload. Without a choice nothing is requested.
    DisposableEffect(reloadKey, canRequestAds, choiceVersion) {
        nativeAd?.destroy()
        nativeAd = null
        if (canRequestAds) {
            val loader = AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_UNIT)
                .forNativeAd { ad -> nativeAd = ad }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) { /* leave slot empty */ }
                })
                .build()
            loader.loadAd(AdRequest.Builder().build())
        }
        onDispose { nativeAd?.destroy() }
    }

    // Reset the 5 s skip countdown for each newly loaded ad.
    LaunchedEffect(reloadKey) {
        elapsed = 0
        while (elapsed < SKIPPABLE_AFTER_MS) {
            kotlinx.coroutines.delay(100)
            elapsed += 100
        }
    }

    // After a dismiss, bring a fresh ad back once the (random 2–4 min) cooldown passes.
    LaunchedEffect(hidden) {
        if (hidden) {
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(RELOAD_MIN_MS, RELOAD_MAX_MS))
            // Not while the app is in the background: no ad request nobody sees.
            lifecycleOwner.lifecycle.withStarted { }
            reloadKey++
            hidden = false
        }
    }

    if (hidden) return
    val ad = nativeAd ?: return
    val canSkip = elapsed >= SKIPPABLE_AFTER_MS

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp
    ) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    val adView = LayoutInflater.from(ctx)
                        .inflate(R.layout.native_ad_view, null) as NativeAdView
                    bindNativeAd(adView, ad)
                    adView
                },
                update = { adView -> bindNativeAd(adView, ad) }
            )

            // "Ad" label (required attribution).
            Text(
                text = stringResource(R.string.ad_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )

            // Countdown ring for 5s, then the close "X".
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (canSkip) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { hidden = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ad_skip),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        progress = { (elapsed / SKIPPABLE_AFTER_MS.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

private fun bindNativeAd(adView: NativeAdView, ad: NativeAd) {
    val headline = adView.findViewById<TextView>(R.id.ad_headline)
    val body = adView.findViewById<TextView>(R.id.ad_body)
    val cta = adView.findViewById<Button>(R.id.ad_cta)

    headline.text = ad.headline
    adView.headlineView = headline

    val bodyText = ad.body
    if (bodyText.isNullOrBlank()) {
        body.text = ""
    } else {
        body.text = bodyText
        adView.bodyView = body
    }

    val ctaText = ad.callToAction
    if (ctaText.isNullOrBlank()) {
        cta.text = "Open"
    } else {
        cta.text = ctaText
        adView.callToActionView = cta
    }

    adView.setNativeAd(ad)
}
