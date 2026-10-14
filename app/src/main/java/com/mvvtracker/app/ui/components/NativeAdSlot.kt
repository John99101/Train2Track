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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.withStarted
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.mvvtracker.app.R
import com.mvvtracker.app.viewmodel.NativeAdViewModel
import com.mvvtracker.app.viewmodel.SettingsViewModel

private const val SKIPPABLE_AFTER_MS = 5_000

/**
 * A real AdMob **native** ad rendered inside our own card, dismissible with an
 * "X" that appears after 5 seconds (a countdown ring shows until then). Native
 * format is used precisely because it lets us own the layout + close control.
 *
 * The advert itself lives in [NativeAdViewModel], so it stays while the user
 * switches between the tabs instead of being requested anew on every visit. After
 * the user dismisses it, the slot hides and a fresh ad comes once the reload
 * cooldown passes. Renders nothing until an ad loads (and nothing on failure), so
 * it never blocks the UI. Uses a TEST ad unit in debug and the real unit in release
 * (BuildConfig.ADMOB_NATIVE_UNIT).
 *
 * Nothing is requested before the user answered Google's consent dialog (see
 * privacy/AdsConsent). The ads software reads that choice by itself, so the
 * request carries no flags of its own.
 */
@Composable
fun NativeAdSlot(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    adViewModel: NativeAdViewModel = hiltViewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val nativeAd by adViewModel.ad.collectAsState()
    val closed by adViewModel.closed.collectAsState()
    var elapsed by remember { mutableIntStateOf(0) }

    val canRequestAds by settingsViewModel.canRequestAds.collectAsState()
    val choiceVersion by settingsViewModel.adChoiceVersion.collectAsState()

    // An ad when one is due (see NativeAdViewModel); without an ad choice nothing is
    // requested. After a dismiss or a failed request: once the pause is over, but
    // not while the app is in the background (no ad request nobody sees).
    LaunchedEffect(canRequestAds, choiceVersion, closed) {
        while (true) {
            lifecycleOwner.lifecycle.withStarted { }
            adViewModel.showing(canRequestAds, choiceVersion)
            val wait = adViewModel.nextLoadAtMs - System.currentTimeMillis()
            if (wait <= 0) break
            kotlinx.coroutines.delay(wait)
        }
    }

    // The 5 s before the ad can be dismissed count once per ad, not once per visit.
    LaunchedEffect(nativeAd) {
        if (nativeAd == null) return@LaunchedEffect
        adViewModel.markShown()
        while (true) {
            elapsed = (System.currentTimeMillis() - adViewModel.shownSinceMs).toInt()
            if (elapsed >= SKIPPABLE_AFTER_MS) break
            kotlinx.coroutines.delay(100)
        }
    }

    if (closed) return
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
                            .clickable { adViewModel.close() },
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
