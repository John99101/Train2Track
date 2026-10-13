package com.mvvtracker.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug: use the debug provider so emulators/dev builds work. On first run,
 * Logcat prints a debug token - add it in Firebase console -> App Check ->
 * Manage debug tokens so this device is accepted while enforcement is on.
 */
internal fun installAppCheck() {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )
}
