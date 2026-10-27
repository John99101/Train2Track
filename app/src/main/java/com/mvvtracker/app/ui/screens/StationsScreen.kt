package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DepartureBoard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.data.model.GERMAN_CITIES
import com.mvvtracker.app.data.station.Departure
import com.mvvtracker.app.data.station.StationRef
import com.mvvtracker.app.ui.components.ModeSymbol
import com.mvvtracker.app.ui.components.OccupancyMeter
import com.mvvtracker.app.ui.components.ReportMark
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.EventsViewModel
import com.mvvtracker.app.viewmodel.StationBoardState
import com.mvvtracker.app.viewmodel.StationBoardViewModel
import com.mvvtracker.app.viewmodel.StationSearchState
import com.mvvtracker.app.viewmodel.StationsViewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** The timetable's mode names for a station (SUBURBAN, ...) as the app's symbols, each once. */
private fun stationSymbols(modes: List<String>): List<String> {
    val mapped = modes.mapNotNull {
        when (it) {
            "SUBURBAN", "METRO" -> "METRO"
            "SUBWAY" -> "SUBWAY"
            "TRAM" -> "TRAM"
            "BUS" -> "BUS"
            "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL" -> "REGIONAL_RAIL"
            else -> null
        }
    }.toSet()
    return listOf("METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL").filter { it in mapped }
}

/**
 * The Stations tab: search a station of the selected city, or open one of the
 * stations opened before. Nothing is loaded until the rider types; stations are
 * not shown on the map.
 */
@Composable
fun StationsScreen(
    citySelectionViewModel: CitySelectionViewModel,
    viewModel: StationsViewModel = hiltViewModel(),
    onOpen: (StationRef) -> Unit
) {
    val city by citySelectionViewModel.selectedCity.collectAsStateWithLifecycle()
    LaunchedEffect(city.id) { viewModel.setCity(city.id) }
    val text by viewModel.text.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val recentHere = recent.filter { it.cityId == city.id }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.nav_stations),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.stations_in_city, city.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::setText,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.stations_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setText("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stations_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        )
        when (val s = search) {
            StationSearchState.Idle ->
                if (recentHere.isEmpty()) {
                    Hint(stringResource(R.string.stations_empty_hint))
                } else {
                    Text(
                        stringResource(R.string.stations_recent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    StationList(recentHere, onOpen)
                }
            StationSearchState.Loading ->
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            is StationSearchState.Results ->
                if (s.stations.isEmpty()) Hint(stringResource(R.string.stations_no_results, city.name))
                else StationList(s.stations, onOpen)
            StationSearchState.Error -> Hint(stringResource(R.string.stations_error))
        }
    }
}

@Composable
private fun StationList(stations: List<StationRef>, onOpen: (StationRef) -> Unit) {
    LazyColumn {
        items(stations, key = { "${it.cityId}|${it.id}" }) { station ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(station) }.padding(vertical = 12.dp)
            ) {
                Icon(Icons.Filled.DepartureBoard, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(station.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val symbols = stationSymbols(station.modes)
                    if (symbols.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                            symbols.forEach { ModeSymbol(it, size = 16.dp) }
                        }
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp)
    )
}

/**
 * One station: its next departures (with the "!" and the occupancy of a journey
 * when riders reported them; a tap opens the journey's report window). Refreshed
 * every minute while on screen.
 */
@Composable
fun StationScreen(
    station: StationRef,
    onBack: () -> Unit,
    onOpenTrain: (line: String, tripId: String, station: String, mode: String, city: String) -> Unit,
    viewModel: StationBoardViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel()
) {
    // Load while the screen is on screen; leaving it stops the refreshing.
    LifecycleStartEffect(station) {
        viewModel.open(station)
        viewModel.resume()
        onStopOrDispose { viewModel.pause() }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val flagged by eventsViewModel.flaggedTripIds.collectAsStateWithLifecycle()
    val occupancy by eventsViewModel.occupancy.collectAsStateWithLifecycle()
    // Minutes until departure count down on the phone between two loads.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val cityName = GERMAN_CITIES.firstOrNull { it.id == station.cityId }?.name.orEmpty()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                }
                Column(Modifier.offset(x = (-8).dp)) {
                    Text(station.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(cityName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when (val s = state) {
            StationBoardState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            StationBoardState.Error -> item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.stations_error), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.station_retry)) }
                }
            }
            is StationBoardState.Loaded -> {
                val board = s.board
                // Departures gone more than half a minute ago drop out before the next load.
                val upcoming = board.departures.filter { it.expectedAtMs >= nowMs - 30_000 }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                        Text(stringResource(R.string.station_departures), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.station_updated, clock(board.loadedAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (upcoming.isEmpty()) {
                    item { Hint(stringResource(R.string.station_no_departures)) }
                } else {
                    items(upcoming, key = { "${it.tripId}|${it.plannedAtMs}|${it.label}" }) { dep ->
                        DepartureRow(
                            dep = dep,
                            nowMs = nowMs,
                            flagged = dep.tripId.isNotBlank() && dep.tripId in flagged,
                            occupancyLevel = occupancy[dep.tripId]?.level,
                            onClick = if (dep.tripId.isBlank()) null
                            else ({ onOpenTrain(dep.label, dep.tripId, board.name.ifBlank { station.name }, dep.mode, station.cityId) })
                        )
                        HorizontalDivider()
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DepartureRow(dep: Departure, nowMs: Long, flagged: Boolean, occupancyLevel: Int?, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp)
    ) {
        val color = Color(trainColorArgb(dep.colorHex, dep.label, dep.mode))
        Box(
            modifier = Modifier.widthIn(min = 44.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(dep.label, color = Color(labelColorOn(color.toArgb())), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                dep.destination,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (dep.cancelled) TextDecoration.LineThrough else null
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dep.platform.isNotBlank()) {
                    Text(
                        stringResource(R.string.station_platform, dep.platform),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (dep.cancelled) {
                    Text(
                        stringResource(R.string.station_cancelled),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    DelayBadge(delayMinutes = dep.delayMinutes, isRealtime = dep.realtime)
                }
                if (occupancyLevel != null) OccupancyMeter(occupancyLevel, segmentWidth = 5.dp, height = 7.dp)
            }
        }
        if (flagged) {
            Spacer(Modifier.width(6.dp))
            ReportMark(size = 20.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            val minutes = ((dep.expectedAtMs - nowMs) / 60_000L).toInt()
            Text(
                if (minutes <= 0) stringResource(R.string.station_now) else stringResource(R.string.station_minutes, minutes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(clock(dep.plannedAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
