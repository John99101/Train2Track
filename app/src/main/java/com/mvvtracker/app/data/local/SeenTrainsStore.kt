package com.mvvtracker.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which trains the user has already opened, so the Feed can dim them
 * (like a read email) and optionally hide them. Stored locally only (no backend,
 * no personal data). Trip ids change per journey, so this naturally becomes stale
 * from one day to the next - which is the intended behaviour.
 */
@Singleton
class SeenTrainsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("seen_trains", Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    fun markSeen(tripId: String?) {
        if (tripId.isNullOrBlank() || tripId in _seen.value) return
        val updated = _seen.value + tripId
        _seen.value = updated
        // Store a fresh set instance (SharedPreferences ignores in-place edits).
        prefs.edit().putStringSet(KEY, HashSet(updated)).apply()
    }

    /** "Delete my data". */
    fun clear() {
        _seen.value = emptySet()
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "seen_trip_ids"
    }
}
