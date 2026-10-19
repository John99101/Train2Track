package com.mvvtracker.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mvvtracker.app.privacy.AdsConsent
import com.mvvtracker.app.util.LocaleHelper
import com.mvvtracker.app.util.ThemeHelper
import com.mvvtracker.app.util.ThemeMode
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.mvvtracker.app.ui.AppNavigation
import com.mvvtracker.app.ui.screens.ConsentScreen
import com.mvvtracker.app.ui.screens.OnboardingScreen
import com.mvvtracker.app.ui.screens.UpdateRequiredScreen
import com.mvvtracker.app.viewmodel.UpdateViewModel
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
            // The status bar and the button bar follow the app's own light/dark choice,
            // not the phone's, and stay see through: without this the system laid its
            // own grey (or black) strip over the buttons and in light mode drew white
            // status icons on a white screen.
            DisposableEffect(darkTheme) {
                val bars = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
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
private fun RootContent(
    onAppReady: () -> Unit,
    viewModel: ConsentViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    // Flow shown once each: consent, then the onboarding tutorial, then the app.
    val seenConsent by viewModel.hasSeenConsent.collectAsState()
    val seenTutorial by viewModel.hasSeenTutorial.collectAsState()
    // A version the owner has blocked (a serious fault) shows only the way to update.
    val updateRequired by updateViewModel.updateRequired.collectAsState()
    // Google's ad consent dialog only comes up in the app itself, never on top of
    // the first start screens. Until it is answered no ad is loaded.
    val appReady = seenConsent == true && seenTutorial == true && !updateRequired
    LaunchedEffect(appReady) { if (appReady) onAppReady() }
    when {
        updateRequired -> UpdateRequiredScreen()
        seenConsent == null || seenTutorial == null -> Unit       // loading (splash)
        seenConsent == false -> ConsentScreen(onFinished = {})     // -> flips flag
        seenTutorial == false -> OnboardingScreen(onFinish = { viewModel.setTutorialSeen() })
        else -> AppNavigation()
    }
}
