package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.data.local.FilterStore
import com.mvvtracker.app.ui.components.ModeSymbol
import com.mvvtracker.app.ui.components.SuggestCityFromLocation
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.EventsViewModel
import com.mvvtracker.app.viewmodel.FiltersViewModel
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import com.mvvtracker.app.viewmodel.SettingsViewModel

/** Map mode categories the filter buttons toggle; [symbol] is the mode its button shows. */
private enum class MapMode(val motisModes: Set<String>, val labelRes: Int, val symbol: String) {
    SBAHN(setOf("METRO"), R.string.label_sbahn, "METRO"),
    UBAHN(setOf("SUBWAY"), R.string.label_ubahn, "SUBWAY"),
    TRAM(setOf("TRAM"), R.string.label_tram, "TRAM"),
    BUS(setOf("BUS"), R.string.label_bus, "BUS"),
    RAIL(
        setOf("REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"),
        R.string.map_other_rail,
        "REGIONAL_RAIL"
    )
}

/** Room the filter buttons take at the top of the map; the notes of the map start below. */
private val FILTER_ROW_HEIGHT = 56.dp

/**
 * The Map tab: a live OpenStreetMap centered on the selected city (from the user's
 * location, or whatever they picked in the Feed) showing every train. Tapping a
 * train opens the Report screen for it. A row of buttons at the top toggles which
 * modes are shown: S-Bahn, U-Bahn, Tram, Bus, other trains. Highlighted buttons are
 * shown; S-Bahn and U-Bahn until the user picks.
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

    // Mode filter: S-Bahn and U-Bahn until the user picks; buses and trams are
    // opt-in (they are numerous and were the main map-performance cost). Kept in the
    // app-wide FilterStore (and on the device), so it survives switching tabs and restarts.
    val filters by filtersViewModel.filters.collectAsStateWithLifecycle()
    val selectedModes = remember(filters.mapModes) {
        (filters.mapModes ?: FilterStore.DEFAULT_MODES)
            .mapNotNull { name -> MapMode.entries.find { it.name == name } }.toSet()
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
            topInset = FILTER_ROW_HEIGHT,
            modifier = Modifier.fillMaxSize()
        )

        // Filter buttons along the top: a highlighted button shows its vehicles, a
        // tap switches it on or off. Scrolls sideways on narrow screens.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapMode.entries.forEach { m ->
                val on = m in selectedModes
                FilterChip(
                    selected = on,
                    onClick = {
                        val modes = if (on) selectedModes - m else selectedModes + m
                        filtersViewModel.setMapModes(modes.mapTo(HashSet()) { it.name })
                    },
                    label = {
                        Text(
                            stringResource(m.labelRes),
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    // A faded symbol on a button that is off, so the state does not rest on colour alone.
                    leadingIcon = { ModeSymbol(m.symbol, size = 18.dp, modifier = Modifier.alpha(if (on) 1f else 0.4f)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = on,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 1.5.dp
                    ),
                    elevation = FilterChipDefaults.filterChipElevation(elevation = 2.dp)
                )
            }
        }
    }
}
