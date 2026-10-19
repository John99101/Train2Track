package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.ui.components.SuggestCityFromLocation
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.EventsViewModel
import com.mvvtracker.app.viewmodel.FiltersViewModel
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import com.mvvtracker.app.viewmodel.SettingsViewModel

/** Map mode categories the on-map filter can toggle (Phase 3). */
private enum class MapMode(val motisModes: Set<String>, val labelRes: Int) {
    SBAHN(setOf("METRO"), R.string.label_sbahn),
    UBAHN(setOf("SUBWAY"), R.string.label_ubahn),
    TRAM(setOf("TRAM"), R.string.label_tram),
    BUS(setOf("BUS"), R.string.label_bus),
    RAIL(
        setOf("REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"),
        R.string.map_other_rail
    )
}

/**
 * The Map tab: a live OpenStreetMap centered on the selected city (from the user's
 * location, or whatever they picked in the Feed) showing every train. Tapping a
 * train opens the Report screen for it. An on-map filter (top-right) toggles which
 * modes are shown — S-Bahn, U-Bahn, Tram, Bus, other rail (all on by default).
 */
@Composable
fun MapScreen(
    liveViewModel: LiveMapViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel(),
    citySelectionViewModel: CitySelectionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    filtersViewModel: FiltersViewModel = hiltViewModel(),
    onTrainClick: (line: String, tripId: String?, station: String?, mode: String?, city: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    // Trains a rider has reported: the map marks them with a "!" corner badge.
    val flaggedTripIds by eventsViewModel.flaggedTripIds.collectAsStateWithLifecycle()
    val selectedCity by citySelectionViewModel.selectedCity.collectAsStateWithLifecycle()
    val preciseLocation by settingsViewModel.preciseLocationConsent.collectAsStateWithLifecycle(initialValue = false)

    // Let the user's location preselect the city (until they pick one manually),
    // shared with the Feed tab.
    SuggestCityFromLocation(liveViewModel, citySelectionViewModel)

    // On-map mode filter: rail on by default; buses & trams are opt-in (they are
    // numerous and were the main map-performance cost). Kept in the app-wide
    // FilterStore (and on the device), so it survives switching tabs and restarts.
    var menuOpen by remember { mutableStateOf(false) }
    val filters by filtersViewModel.filters.collectAsStateWithLifecycle()
    val selectedModes = remember(filters.mapModes) {
        filters.mapModes?.mapNotNull { name -> MapMode.entries.find { it.name == name } }?.toSet()
            ?: setOf(MapMode.SBAHN, MapMode.UBAHN, MapMode.RAIL)
    }
    // null = show everything (all categories selected); otherwise the union of
    // the selected categories' MOTIS modes.
    val modeFilter: Set<String>? = remember(selectedModes) {
        if (selectedModes.size == MapMode.entries.size) null
        else selectedModes.flatMap { it.motisModes }.toSet()
    }
    // Download only the vehicles of these modes (rail alone by default).
    LaunchedEffect(modeFilter) { liveViewModel.showModes(modeFilter) }

    Box(modifier = Modifier.fillMaxSize()) {
        LiveTrainMap(
            liveViewModel = liveViewModel,
            centerLat = selectedCity.latitude,
            centerLon = selectedCity.longitude,
            modeFilter = modeFilter,
            flaggedTripIds = flaggedTripIds,
            showUserLocation = preciseLocation,
            onTrainClick = onTrainClick,
            modifier = Modifier.fillMaxSize()
        )

        // Filter control (top-right): toggle which modes appear on the map.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.small,
                shadowElevation = 2.dp
            ) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_filter_right),
                        contentDescription = stringResource(R.string.feed_filter)
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MapMode.entries.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(stringResource(m.labelRes)) },
                        leadingIcon = { Checkbox(checked = m in selectedModes, onCheckedChange = null) },
                        onClick = {
                            val modes = if (m in selectedModes) selectedModes - m else selectedModes + m
                            filtersViewModel.setMapModes(modes.mapTo(HashSet()) { it.name })
                        }
                    )
                }
            }
        }
    }
}
