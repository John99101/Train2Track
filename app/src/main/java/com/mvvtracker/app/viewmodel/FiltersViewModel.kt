package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import com.mvvtracker.app.data.local.FilterStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The Feed and Map filters. The state lives in the app-wide [FilterStore] (and on
 * the device), so every screen sees the same filters and they survive switching
 * tabs, opening a train and closing the app.
 */
@HiltViewModel
class FiltersViewModel @Inject constructor(
    private val store: FilterStore
) : ViewModel() {

    val filters: StateFlow<FilterStore.Filters> = store.filters

    fun setFeedModes(names: Set<String>) = store.setFeedModes(names)

    fun setFeedLine(cityId: String, line: String?) = store.setFeedLine(cityId, line)

    fun setMapModes(names: Set<String>) = store.setMapModes(names)

    fun setMapStations(on: Boolean) = store.setMapStations(on)
}
