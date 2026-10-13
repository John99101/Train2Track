package com.mvvtracker.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mvvtracker.app.privacy.AdsConsent
import com.mvvtracker.app.util.LocaleHelper
import com.mvvtracker.app.util.ThemeHelper
import com.mvvtracker.app.util.ThemeMode
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.mvvtracker.app.ui.AppNavigation
import com.mvvtracker.app.ui.screens.ConsentScreen
import com.mvvtracker.app.ui.screens.OnboardingScreen
import com.mvvtracker.app.ui.theme.MvvTrackerTheme
import com.mvvtracker.app.viewmodel.ConsentViewModel
import androidx.compose.runtime.collectAsState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var adsConsent: AdsConsent

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ThemeHelper.init(this)
        setContent {
            val themeMode by ThemeHelper.mode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MvvTrackerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootContent(onAppReady = { adsConsent.gather(this@MainActivity) })
                }
            }
        }
    }
}

@Composable
private fun RootContent(onAppReady: () -> Unit, viewModel: ConsentViewModel = hiltViewModel()) {
    // Flow shown once each: consent, then the onboarding tutorial, then the app.
    val seenConsent by viewModel.hasSeenConsent.collectAsState()
    val seenTutorial by viewModel.hasSeenTutorial.collectAsState()
    // Google's ad consent dialog only comes up in the app itself, never on top of
    // the first start screens. Until it is answered no ad is loaded.
    val appReady = seenConsent == true && seenTutorial == true
    LaunchedEffect(appReady) { if (appReady) onAppReady() }
    when {
        seenConsent == null || seenTutorial == null -> Unit       // loading (splash)
        seenConsent == false -> ConsentScreen(onFinished = {})     // -> flips flag
        seenTutorial == false -> OnboardingScreen(onFinish = { viewModel.setTutorialSeen() })
        else -> AppNavigation()
    }
}
