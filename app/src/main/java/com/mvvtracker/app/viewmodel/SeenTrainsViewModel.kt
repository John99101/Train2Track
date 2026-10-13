package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import com.mvvtracker.app.data.local.SeenTrainsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Exposes the (app-wide, persistent) set of already-opened trains to the Feed. */
@HiltViewModel
class SeenTrainsViewModel @Inject constructor(
    private val store: SeenTrainsStore
) : ViewModel() {
    val seen: StateFlow<Set<String>> = store.seen
    fun markSeen(tripId: String?) = store.markSeen(tripId)
}
