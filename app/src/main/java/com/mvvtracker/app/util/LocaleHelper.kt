package com.mvvtracker.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app language override, stored in SharedPreferences so it can be read
 * synchronously in Activity.attachBaseContext (before any UI is created).
 * An empty tag means "follow the system language".
 */
object LocaleHelper {
    private const val PREFS = "locale_prefs"
    private const val KEY_LANG = "lang"

    /** Current override tag ("" = system, "en", "de"). */
    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "") ?: ""

    fun setLanguage(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, tag).apply()
    }

    /** Wraps [context] with the chosen locale (no-op when following the system). */
    fun wrap(context: Context): Context {
        val tag = language(context)
        if (tag.isEmpty()) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}

/** Walks the context chain to find the hosting Activity (to recreate it). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
