package com.mvvtracker.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.LiveMapViewModel

/**
 * Lets the user's location preselect the city (until they pick one manually).
 * Used by the Feed and the Map, which share the selected city.
 */
@Composable
fun SuggestCityFromLocation(liveViewModel: LiveMapViewModel, citySelectionViewModel: CitySelectionViewModel) {
    val nearestCity by liveViewModel.nearestCity.collectAsStateWithLifecycle()
    LaunchedEffect(nearestCity) {
        nearestCity?.let { citySelectionViewModel.suggestFromLocation(it) }
    }
}
