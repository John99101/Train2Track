package com.mvvtracker.app.util

import com.mvvtracker.app.BuildConfig

/** Who the app is towards the outside: its addresses and how it names itself. */
object AppInfo {
    const val WEBSITE = "https://example.invalid"
    const val CONTACT_MAIL = "contact@example.invalid"

    /** The public source code of the apps and the server. */
    const val SOURCE_CODE_URL = "https://github.com/John99101/Train2Track"

    /** Where unlawful content can be reported (Article 16 Digital Services Act). */
    const val REPORT_CONTENT_URL = "$WEBSITE/report"

    /**
     * Sent with every request to the timetable service (Transitous) and to the map
     * tile servers (OpenStreetMap). Both ask apps to say who they are and how the
     * operator can be reached: name, version, website and contact.
     */
    val USER_AGENT = "Train2Track/${BuildConfig.VERSION_NAME} (Android; +$WEBSITE; $CONTACT_MAIL)"
}
