package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.data.model.GERMAN_CITIES
import com.mvvtracker.app.ui.components.ModeSymbol
import com.mvvtracker.app.ui.components.NativeAdSlot
import com.mvvtracker.app.ui.components.ReportMark
import com.mvvtracker.app.ui.components.SuggestCityFromLocation
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.EventsViewModel
import com.mvvtracker.app.viewmodel.FiltersViewModel
import com.mvvtracker.app.viewmodel.LiveMapViewModel
import com.mvvtracker.app.viewmodel.SeenTrainsViewModel
import com.mvvtracker.app.viewmodel.TrainPosition

/**
 * Feed filters, combinable: any of S-Bahn / U-Bahn / Bus / Tram (shown together),
 * optionally narrowed to the currently-reported trains (most-reported first).
 * No filter selected = "All". Order matches the filter menu.
 */
private enum class FeedMode(val motisMode: String?) {
    SBAHN("METRO"), UBAHN("SUBWAY"), BUS("BUS"), TRAM("TRAM"), REPORTS(null)
}

/** True when a vehicle of [mode] passes the selected mode filters (none = all). */
private fun Set<FeedMode>.allowsMode(mode: String): Boolean {
    val modes = mapNotNull { it.motisMode }
    return modes.isEmpty() || mode in modes
}

private fun Set<FeedMode>.toggle(m: FeedMode): Set<FeedMode> = if (m in this) this - m else this + m

// Local-transit modes shown in the Feed: rapid transit plus buses and trams
// (regional/long-distance rail is left to the Map).
private val FEED_MODES = setOf("METRO", "SUBWAY", "BUS", "TRAM")

/** String resource for a mode's short label (S-Bahn / U-Bahn / Bus / Tram). */
private fun modeLabelRes(mode: String): Int = when (mode) {
    "METRO" -> R.string.label_sbahn
    "SUBWAY" -> R.string.label_ubahn
    "BUS" -> R.string.label_bus
    "TRAM" -> R.string.label_tram
    else -> R.string.label_sbahn
}

/**
 * The Feed tab: pick a German city and see a live LIST of its S-Bahn and U-Bahn
 * trains - each row shows the line, where it just left and where it's heading.
 * Lines that a rider has an active report on are marked with a red "!".
 *
 * Filtering (Phase 1): one filter button (All / S-Bahn / U-Bahn / Current reports),
 * a dropdown of the currently-active lines, and a search box matching line OR station.
 * Trains already opened are dimmed (seen state); there is no "hide seen" toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFeedScreen(
    liveViewModel: LiveMapViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel(),
    citySelectionViewModel: CitySelectionViewModel = hiltViewModel(),
    seenTrainsViewModel: SeenTrainsViewModel = hiltViewModel(),
    filtersViewModel: FiltersViewModel = hiltViewModel(),
    onReportTrain: (line: String, tripId: String?, station: String?, mode: String?, city: String) -> Unit =
        { _, _, _, _, _ -> }
) {
    val flaggedTripIds by eventsViewModel.flaggedTripIds.collectAsStateWithLifecycle()
    val remoteCounts by eventsViewModel.remoteReportCounts.collectAsStateWithLifecycle()
    val delayVotes by eventsViewModel.delayVotes.collectAsStateWithLifecycle()
    val disruptions by eventsViewModel.disruptions.collectAsStateWithLifecycle()
    val trains by liveViewModel.trains.collectAsStateWithLifecycle()
    val loadError by liveViewModel.loadError.collectAsStateWithLifecycle()
    val dataDelayed by liveViewModel.dataDelayed.collectAsStateWithLifecycle()

    // Selected city is held in a shared, session-long ViewModel so the choice
    // survives tab switches and stays in sync with the Map tab.
    val selectedCity by citySelectionViewModel.selectedCity.collectAsStateWithLifecycle()
    var cityExpanded by remember { mutableStateOf(false) }

    // Feed filters: modes (several at once, none = all) and/or current reports, and
    // a single line. They live in the app-wide FilterStore (and on the device), so
    // they survive switching tabs, opening a train and closing the app. The line
    // only applies in the city it was picked in. The search text is per visit.
    val filters by filtersViewModel.filters.collectAsStateWithLifecycle()
    val modeFilters = remember(filters.feedModes) {
        filters.feedModes.mapNotNull { name -> FeedMode.entries.find { it.name == name } }.toSet()
    }
    val setModeFilters: (Set<FeedMode>) -> Unit = { modes ->
        filtersViewModel.setFeedModes(modes.mapTo(HashSet()) { it.name })
    }
    val selectedLine = filters.feedLineIn(selectedCity.id)   // null = all lines
    val setSelectedLine: (String?) -> Unit = { line -> filtersViewModel.setFeedLine(selectedCity.id, line) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var lineExpanded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Changing a filter jumps back to the top of the list (but returning to the
    // Feed with the same filters keeps the scroll position).
    val listState = rememberLazyListState()
    val filterSignature = "${modeFilters.sorted()}|$selectedLine|$query"
    var lastFilterSignature by rememberSaveable { mutableStateOf(filterSignature) }
    LaunchedEffect(filterSignature) {
        if (filterSignature != lastFilterSignature) {
            lastFilterSignature = filterSignature
            listState.scrollToItem(0)
        }
    }

    // A newly reported train moves to the top of the list. When the list is at (or
    // near) the top, follow it: otherwise the "!" would sit just above the visible
    // part, e.g. right after reporting a train and coming back.
    LaunchedEffect(flaggedTripIds) {
        if (listState.firstVisibleItemIndex <= 2) listState.scrollToItem(0)
    }

    // Already-opened trains are dimmed in the list (no hide toggle).
    val seenTrains by seenTrainsViewModel.seen.collectAsStateWithLifecycle()

    // Opens the selected city's operator ticket page (the "buy a ticket" shortcut).
    val uriHandler = LocalUriHandler.current

    // Preselect the user's city from their location (until they pick one manually).
    SuggestCityFromLocation(liveViewModel, citySelectionViewModel)

    // Only poll while the Feed is on screen AND the app is in the foreground:
    // leaving the app (home button, screen off) stops the downloads too.
    LifecycleStartEffect(liveViewModel) {
        liveViewModel.resume()
        onStopOrDispose { liveViewModel.pause() }
    }

    // List the selected city's whole network (no map here).
    LaunchedEffect(selectedCity) { liveViewModel.showCity(selectedCity) }
    // Download only the modes of the selected filters ("All" and "Current reports" need every mode).
    val feedMotisModes = remember(modeFilters) { modeFilters.mapNotNullTo(HashSet()) { it.motisMode }.ifEmpty { null } }
    LaunchedEffect(feedMotisModes) { liveViewModel.showModes(feedMotisModes) }

    // Lines currently active for the chosen mode, used to populate the line
    // dropdown. Just the line labels (e.g. "S1", "U6"), distinct and sorted.
    val activeLines = remember(trains, modeFilters) {
        trains
            .filter { it.mode in FEED_MODES && modeFilters.allowsMode(it.mode) }
            .map { it.label }
            .distinct()
            .sortedWith(compareBy({ it.length }, { it }))
    }

    val reportsOnly = FeedMode.REPORTS in modeFilters

    // Local transit, de-duplicated, then narrowed by the selected modes (several
    // can be combined), "Current reports", the selected line, and the search box
    // (line OR current/destination station).
    //  - "Current reports" shows only flagged trains, most-reported first.
    //  - otherwise a reported train floats to the top.
    val suTrains = remember(trains, flaggedTripIds, remoteCounts, modeFilters, selectedLine, query, seenTrains) {
        val q = query.trim()
        val filtered = trains
            .filter { it.mode in FEED_MODES && modeFilters.allowsMode(it.mode) }
            .filter { !reportsOnly || (it.tripId != null && it.tripId in flaggedTripIds) }
            .filter { selectedLine == null || it.label == selectedLine }
            .filter {
                q.isBlank() ||
                    it.label.contains(q, ignoreCase = true) ||
                    (it.fromStop?.contains(q, ignoreCase = true) == true) ||
                    (it.toStop?.contains(q, ignoreCase = true) == true)
            }
            .distinctBy { it.tripId ?: "${it.label}|${it.fromStop}|${it.toStop}" }
        if (reportsOnly) {
            // Most-reported (most corroborations) at the very top.
            filtered.sortedWith(
                compareByDescending<TrainPosition> { remoteCounts[it.tripId] ?: 0 }
                    .thenBy { it.label }
            )
        } else {
            filtered.sortedWith(
                compareByDescending<TrainPosition> { it.tripId != null && it.tripId in flaggedTripIds }
                    .thenBy { it.label }
                    .thenBy { it.toStop ?: "" }
            )
        }
    }

    // Spoken label of the active filters (for the filter button's description).
    val modeLabels = FeedMode.entries.filter { it in modeFilters }.map {
        when (it) {
            FeedMode.SBAHN -> stringResource(R.string.label_sbahn)
            FeedMode.UBAHN -> stringResource(R.string.label_ubahn)
            FeedMode.BUS -> stringResource(R.string.label_bus)
            FeedMode.TRAM -> stringResource(R.string.label_tram)
            FeedMode.REPORTS -> stringResource(R.string.feed_filter_reports)
        }
    }
    val modeLabel = modeLabels.joinToString(", ").ifEmpty { stringResource(R.string.feed_filter_all) }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        ExposedDropdownMenuBox(
            expanded = cityExpanded,
            onExpandedChange = { cityExpanded = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            OutlinedTextField(
                value = selectedCity.name,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.feed_city)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cityExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = cityExpanded,
                onDismissRequest = { cityExpanded = false }
            ) {
                GERMAN_CITIES.forEach { city ->
                    DropdownMenuItem(
                        text = { Text(city.name) },
                        onClick = {
                            citySelectionViewModel.selectManually(city)
                            cityExpanded = false
                        }
                    )
                }
            }
        }

        // One line: [filter icon]  [search]  [line dropdown].
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = { filterMenuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_filter_right),
                        contentDescription = "$modeLabel · ${stringResource(R.string.feed_filter)}"
                    )
                }
                // Tiny symbols of the active filters in the button's bottom left corner.
                if (modeFilters.isNotEmpty()) {
                    ActiveFilterBadges(
                        modes = FeedMode.entries.filter { it in modeFilters },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 2.dp, bottom = 3.dp)
                    )
                }
                // Several modes can be ticked at once (the menu stays open); "All"
                // clears them. Unticking the last one is "All" again.
                DropdownMenu(
                    expanded = filterMenuOpen,
                    onDismissRequest = { filterMenuOpen = false }
                ) {
                    FilterMenuItem(stringResource(R.string.feed_filter_all), modeFilters.isEmpty()) {
                        setModeFilters(emptySet()); filterMenuOpen = false
                    }
                    // The four modes show as symbols instead of text.
                    listOf(FeedMode.SBAHN, FeedMode.UBAHN, FeedMode.BUS, FeedMode.TRAM).forEach { m ->
                        FilterModeItem(m.motisMode!!, m in modeFilters) { setModeFilters(modeFilters.toggle(m)) }
                    }
                    FilterMenuItem(stringResource(R.string.feed_filter_reports), reportsOnly) {
                        setModeFilters(modeFilters.toggle(FeedMode.REPORTS))
                    }
                }
            }

            // Search a line or a station, sitting between the two controls.
            CompactSearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.feed_search_hint),
                modifier = Modifier.weight(1f)
            )

            // Line dropdown: compact (wrap width, short height), to the right of
            // the search. Pick one active line, or all lines.
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { lineExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLine ?: stringResource(R.string.feed_all_lines),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.feed_line)
                    )
                }
                DropdownMenu(
                    expanded = lineExpanded,
                    onDismissRequest = { lineExpanded = false },
                    // Cap the height so a long line list opens under the button and
                    // scrolls, rather than running down over the bottom navigation.
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feed_all_lines)) },
                        onClick = { setSelectedLine(null); lineExpanded = false }
                    )
                    activeLines.forEach { line ->
                        DropdownMenuItem(
                            text = { Text(line) },
                            onClick = { setSelectedLine(line); lineExpanded = false }
                        )
                    }
                }
            }
        }

        Text(
            text = when {
                // Our server is unreachable (the app asks the source directly, less
                // often) or serves an older snapshot: say so instead of "live".
                suTrains.isNotEmpty() && dataDelayed ->
                    pluralStringResource(R.plurals.feed_trains_delayed, suTrains.size, suTrains.size, selectedCity.name)
                suTrains.isNotEmpty() ->
                    pluralStringResource(R.plurals.feed_trains_live, suTrains.size, suTrains.size, selectedCity.name)
                loadError -> stringResource(R.string.feed_unavailable)
                reportsOnly && trains.isNotEmpty() ->
                    stringResource(R.string.feed_no_reports)
                else -> stringResource(R.string.feed_looking, selectedCity.name)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Official disruptions (Phase 6 / Option 2b): the `disruptions` collection is
        // nationwide, so keep only those that mention the selected city (the gtfs.de
        // alerts carry no coordinates, so we match the city name in the text).
        val localDisruptions = remember(disruptions, selectedCity.name) {
            val needle = selectedCity.name.lowercase()
            disruptions.filter { d ->
                (d.title + " " + d.description + " " + d.affected.joinToString(" "))
                    .lowercase().contains(needle)
            }
        }
        if (localDisruptions.isNotEmpty()) {
            DisruptionsBanner(localDisruptions)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(
                suTrains,
                key = { it.tripId ?: "${it.label}|${it.fromStop}|${it.toStop}" }
            ) { train ->
                // Crowd confirmations: only when more riders confirm a delay than
                // call it on time (so a single stray vote doesn't light up the row).
                val votes = train.tripId?.let { delayVotes[it] }
                val delayConfirmCount = votes?.let { (c, o) -> if (c > o) c else 0 } ?: 0
                TrainRow(
                    train = train,
                    flagged = train.tripId != null && train.tripId in flaggedTripIds,
                    seen = train.tripId != null && train.tripId in seenTrains,
                    delayConfirmCount = delayConfirmCount,
                    onClick = {
                        seenTrainsViewModel.markSeen(train.tripId)
                        onReportTrain(train.label, train.tripId, train.fromStop ?: train.toStop, train.mode, train.city)
                    },
                    onBuyTicket = { uriHandler.openUri(selectedCity.ticketUrl) }
                )
            }
        }
      }

      // Loading spinner until the first trains arrive (not just an empty filter).
      if (trains.isEmpty() && !loadError) {
          CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      }

      // Ad slot: a real AdMob (test) native ad, skippable after 5s via an "X".
      // Self-reloads after a dismiss, so it doesn't disappear for the whole session.
      NativeAdSlot(
          modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(12.dp)
      )
    }
}

/** Compact single-line search box (about half a text field's height) used inline
 *  between the filter icon and the line dropdown. */
@Composable
private fun CompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { onQueryChange("") }
            )
        }
    }
}

/** One row in the filter menu, with a check on the currently-selected mode. */
@Composable
private fun FilterMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else null
    )
}

/**
 * The active filters as tiny symbols (a few dp each) for the corner of the filter
 * button, so the user sees at a glance what the list is narrowed to: the mode
 * badges, plus a red "!" for "Current reports".
 */
@Composable
private fun ActiveFilterBadges(modes: List<FeedMode>, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        modes.forEach { m ->
            val motis = m.motisMode
            if (motis != null) {
                ModeSymbol(motis, size = 10.dp)
            } else {
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFDC2828)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "!",
                        color = Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(lineHeight = 7.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

/** A filter menu row showing a mode as its symbol (S/U/Tram/Bus) instead of text. */
@Composable
private fun FilterModeItem(mode: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { ModeSymbol(mode) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else null
    )
}

@Composable
private fun TrainRow(
    train: TrainPosition,
    flagged: Boolean,
    seen: Boolean,
    delayConfirmCount: Int = 0,
    onClick: () -> Unit,
    onBuyTicket: () -> Unit
) {
    Card(
        onClick = onClick,
        // A subtle grey tint on trains already opened (text stays crisp — no fade).
        colors = CardDefaults.cardColors(
            containerColor = if (seen) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LineBadge(train.label, Color(trainColorArgb(train.colorHex, train.label, train.mode)))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Current station in bold on top, destination (with the arrow) below;
                // both shrink to fit one line when the name is long.
                AutoResizeText(
                    text = train.fromStop ?: "…",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth()
                )
                AutoResizeText(
                    text = "→  ${train.toStop ?: "…"}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(modeLabelRes(train.mode)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    DelayBadge(delayMinutes = train.delayMinutes, isRealtime = train.isRealtime)
                    if (delayConfirmCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = pluralStringResource(R.plurals.delay_confirmed_count, delayConfirmCount, delayConfirmCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (flagged) {
                Spacer(Modifier.width(8.dp))
                ReportMark()
                // Quick "buy a valid ticket" shortcut on reported trains: opens the
                // selected city's operator ticket page.
                IconButton(onClick = onBuyTicket) {
                    Icon(
                        Icons.Filled.ConfirmationNumber,
                        contentDescription = stringResource(R.string.buy_ticket),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Single-line text that shrinks its font until it fits the available width, so a
 * long station name still fits the row (rather than wrapping or being clipped) —
 * Compose 1.6 has no built-in autoSize. Never grows past [style]'s size.
 */
@Composable
private fun AutoResizeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minFontSizeSp: Float = 10f
) {
    var resized by remember(text, style) { mutableStateOf(style) }
    Text(
        text = text,
        style = resized,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier,
        onTextLayout = { result ->
            if (result.didOverflowWidth && resized.fontSize.value > minFontSizeSp) {
                val next = (resized.fontSize.value * 0.92f).coerceAtLeast(minFontSizeSp)
                resized = resized.copy(fontSize = next.sp)
            }
        }
    )
}

/**
 * Collapsible disruptions banner (Phase 6 / Option 2b): a one-line summary of how
 * many official disruptions are active; tap to expand the list. Fed by the free
 * nationwide gtfs.de GTFS-RT alerts.
 */
@Composable
private fun DisruptionsBanner(disruptions: List<com.mvvtracker.app.data.model.Disruption>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(R.plurals.disruptions_banner, disruptions.size, disruptions.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(if (expanded) R.string.disruptions_hide else R.string.disruptions_show),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (expanded) {
                // Cap the height and scroll inside, so a long list does not push the
                // feed off screen and stays readable within the banner.
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    disruptions.forEach { d ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            d.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (d.description.isNotBlank()) {
                            Text(
                                d.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small live-status chip (Phase 6 / Option 1): shows the real-time delay
 * ("On time" / "+5 min" / "3 min early" in green/amber/red), or a neutral grey
 * "Scheduled" when the trip has no live feed — so the user knows when a train is
 * drawn at its timetable position rather than where it actually is.
 */
@Composable
private fun DelayBadge(delayMinutes: Int?, isRealtime: Boolean) {
    data class Chip(val text: String, val bg: Color, val fg: Color)
    val chip = when {
        !isRealtime || delayMinutes == null -> Chip(
            stringResource(R.string.delay_scheduled),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        delayMinutes <= 0 && delayMinutes >= -1 -> Chip(
            stringResource(R.string.delay_on_time), Color(0xFF1B5E20), Color.White
        )
        delayMinutes < -1 -> Chip(
            stringResource(R.string.delay_early, -delayMinutes), Color(0xFF1B5E20), Color.White
        )
        delayMinutes in 1..4 -> Chip(
            stringResource(R.string.delay_late, delayMinutes), Color(0xFFB26A00), Color.White
        )
        else -> Chip(
            stringResource(R.string.delay_late, delayMinutes), Color(0xFFC62828), Color.White
        )
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(chip.bg)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(chip.text, color = chip.fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun LineBadge(label: String, color: Color) {
    Box(
        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(labelColorOn(color.toArgb())),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}
