package com.mvvtracker.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Light/dark preference: follow the system, or force light/dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-wide theme override, stored in SharedPreferences and mirrored in a
 * StateFlow so the root composable re-themes instantly when it changes (no
 * Activity recreate needed). Parallels [LocaleHelper] for language.
 */
object ThemeHelper {
    private const val PREFS = "theme_prefs"
    private const val KEY_MODE = "mode"

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    /** Load the stored preference into the flow (call once, before first compose). */
    fun init(context: Context) {
        _mode.value = read(context)
    }

    fun setMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    private fun read(context: Context): ThemeMode =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
}
