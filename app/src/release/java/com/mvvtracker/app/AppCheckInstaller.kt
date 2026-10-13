package com.mvvtracker.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release: attest with Play Integrity (real device attestation). */
internal fun installAppCheck() {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        PlayIntegrityAppCheckProviderFactory.getInstance()
    )
}
