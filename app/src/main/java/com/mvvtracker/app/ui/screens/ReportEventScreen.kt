package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.data.model.MovementType
import com.mvvtracker.app.data.repository.ReportAggregation
import com.mvvtracker.app.ui.components.ModeSymbol
import com.mvvtracker.app.ui.components.OCCUPANCY_KNOB
import com.mvvtracker.app.ui.components.OccupancyMeter
import com.mvvtracker.app.ui.components.OccupancySlider
import com.mvvtracker.app.ui.components.occupancyLabelRes
import com.mvvtracker.app.ui.components.ReportMark
import com.mvvtracker.app.ui.components.ReportRed
import com.mvvtracker.app.util.AppInfo
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.DelayVoteState
import com.mvvtracker.app.viewmodel.EventsViewModel
import com.mvvtracker.app.viewmodel.OccupancyVoteState
import kotlinx.coroutines.delay
import com.mvvtracker.app.viewmodel.ReportSubmitState
import com.mvvtracker.app.viewmodel.ReportViewModel

/**
 * Report screen for ONE specific train (reached by tapping it). The line and the
 * current station are fixed (from the tapped train). Three grouped cards: a ticket
 * inspection report (on board / has left + submit), how full the train is (a bar
 * to pull + submit) and a crowd delay vote.
 */
@Composable
fun ReportEventScreen(
    reportViewModel: ReportViewModel = hiltViewModel(),
    citySelectionViewModel: CitySelectionViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel(),
    preselectedLine: String? = null,
    tripId: String? = null,
    currentStation: String? = null,
    mode: String? = null,
    // The report city of the train (a city id or "other"), see reportCityAt.
    city: String? = null,
    // Back to the screen the train was opened from (Feed or Map).
    onBack: () -> Unit = {}
) {
    val submitState by reportViewModel.submitState.collectAsStateWithLifecycle()
    val delayVoteState by reportViewModel.delayVoteState.collectAsStateWithLifecycle()
    val selectedCity by citySelectionViewModel.selectedCity.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var movement by remember { mutableStateOf(MovementType.ENTERED) }

    // Is there a current report on this journey, and when and where was the newest made?
    val flaggedTripIds by eventsViewModel.flaggedTripIds.collectAsStateWithLifecycle()
    val reportedTrips by eventsViewModel.reportedTrips.collectAsStateWithLifecycle()
    val flagged = tripId != null && tripId in flaggedTripIds
    val report = reportedTrips[tripId]?.takeIf { it.flagged > 0 }
    // The age is counted on the phone, so it also moves on while no new data arrives.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    val reportedMinutes = report?.reportedAtMs?.let { ReportAggregation.minutesSince(it, nowMs) }

    // How full the journey is, from the riders' votes (shown from two riders on).
    val occupancyByTrip by eventsViewModel.occupancy.collectAsStateWithLifecycle()
    val occupancyVoteState by reportViewModel.occupancyVoteState.collectAsStateWithLifecycle()
    val occupancy = tripId?.let { occupancyByTrip[it] }
    val crowdLevel = occupancy?.level
    val reportedStation = report?.station

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulled left so the arrow lines up with the text below it.
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back)
                )
            }
            Text(
                stringResource(R.string.report_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.offset(x = (-8).dp)
            )
        }
        Text(
            stringResource(R.string.report_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        if (tripId.isNullOrBlank()) {
            Text(
                stringResource(R.string.report_no_train),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        // Train identity: mode symbol + line, with the current station underneath.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mode != null) {
                    ModeSymbol(mode, size = 34.dp)
                    Spacer(Modifier.width(14.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        preselectedLine ?: stringResource(R.string.report_line),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        currentStation ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (crowdLevel != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            OccupancyMeter(crowdLevel)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(occupancyLabelRes(crowdLevel)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // A current report on this journey: the same "!" as in the Feed and on
                // the Map, with when and where the newest report was made.
                if (flagged) {
                    Spacer(Modifier.width(12.dp))
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.widthIn(max = 170.dp)
                    ) {
                        ReportMark(size = 28.dp)
                        if (reportedMinutes != null) {
                            Text(
                                if (reportedMinutes == 0) stringResource(R.string.report_made_now)
                                else stringResource(R.string.report_made_minutes, reportedMinutes),
                                style = MaterialTheme.typography.labelMedium,
                                color = ReportRed,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (reportedStation != null) {
                            Text(
                                stringResource(R.string.report_made_station, reportedStation),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Ticket inspection report.
        SectionCard {
            SectionHeader(Icons.Filled.Shield, stringResource(R.string.report_inspection_title))
            Text(
                stringResource(R.string.report_control_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MovementType.entries.forEach { m ->
                    FilterChip(
                        selected = movement == m,
                        onClick = { movement = m },
                        label = { Text(stringResource(movementLabelRes(m))) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Button(
                onClick = {
                    reportViewModel.submit(
                        tripId = tripId,
                        line = preselectedLine,
                        station = currentStation,
                        movement = movement,
                        city = city
                    )
                },
                enabled = submitState !is ReportSubmitState.Submitting,
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.report_submit))
            }
            when (val state = submitState) {
                is ReportSubmitState.Submitting ->
                    StatusLine(stringResource(R.string.report_submitting), MaterialTheme.colorScheme.onSurfaceVariant)
                is ReportSubmitState.Success ->
                    StatusLine(
                        stringResource(
                            when (state.movement) {
                                MovementType.ENTERED -> R.string.report_success_entered
                                MovementType.EXITED -> R.string.report_success_exited
                            }
                        ),
                        MaterialTheme.colorScheme.primary
                    )
                // A plain sentence instead of the technical error text.
                is ReportSubmitState.Error ->
                    StatusLine(stringResource(R.string.report_error_send), MaterialTheme.colorScheme.error)
                ReportSubmitState.Idle -> Unit
            }
        }

        Spacer(Modifier.height(14.dp))

        // How full is it? A bar to pull, green to red, sent only with Submit (own
        // collection, never affects the "!"). Kept to three short rows.
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(Icons.Filled.Groups, stringResource(R.string.occupancy_title))
                Spacer(Modifier.weight(1f))
                // What riders say now (the average of the current votes) and how many.
                if (crowdLevel != null && occupancy != null) {
                    OccupancyMeter(crowdLevel)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pluralStringResource(R.plurals.occupancy_votes, occupancy.votes, occupancy.votes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // The knob starts at this rider's current vote, else at what riders say,
            // else in the middle; pulling it only moves it.
            var pulled by remember(tripId) { mutableStateOf<Int?>(null) }
            val shownLevel = pulled ?: occupancy?.ownLevel ?: crowdLevel ?: 3
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    OccupancySlider(level = shownLevel, onLevelChange = { pulled = it })
                    Text(
                        stringResource(occupancyLabelRes(shownLevel)),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Under the bar's start (the knob's centre at level 1).
                        modifier = Modifier.padding(start = OCCUPANCY_KNOB / 2)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { reportViewModel.voteOccupancy(tripId, shownLevel, city) },
                    enabled = occupancyVoteState !is OccupancyVoteState.Submitting,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) { Text(stringResource(R.string.occupancy_submit)) }
            }
            when (val s = occupancyVoteState) {
                is OccupancyVoteState.Done ->
                    StatusLine(
                        stringResource(R.string.occupancy_thanks, stringResource(occupancyLabelRes(s.level))),
                        MaterialTheme.colorScheme.primary
                    )
                is OccupancyVoteState.Error ->
                    StatusLine(stringResource(R.string.report_error_send), MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }

        Spacer(Modifier.height(14.dp))

        // Crowd delay vote (separate backend collection; never affects the "!").
        SectionCard {
            SectionHeader(Icons.Filled.Schedule, stringResource(R.string.delay_vote_title))
            Text(
                stringResource(R.string.delay_vote_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { reportViewModel.voteDelay(tripId, confirm = true, city = city) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.delay_vote_yes)) }
                OutlinedButton(
                    onClick = { reportViewModel.voteDelay(tripId, confirm = false, city = city) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.delay_vote_no)) }
            }
            if (delayVoteState is DelayVoteState.Done) {
                StatusLine(
                    stringResource(
                        if ((delayVoteState as DelayVoteState.Done).confirmed) R.string.delay_vote_thanks_late
                        else R.string.delay_vote_thanks_on_time
                    ),
                    MaterialTheme.colorScheme.primary
                )
            } else if (delayVoteState is DelayVoteState.Error) {
                StatusLine(stringResource(R.string.report_error_send), MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(14.dp))

        // Carry a valid ticket: one tap to the selected city's operator ticket page.
        OutlinedButton(
            onClick = { uriHandler.openUri(selectedCity.ticketUrl) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ConfirmationNumber, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.buy_ticket))
        }

        Text(
            stringResource(R.string.report_legal_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )

        // Reports of other riders are shown on this journey: the way to report
        // unlawful content belongs where the content is (Art. 16 Digital Services Act).
        TextButton(
            onClick = { uriHandler.openUri(AppInfo.REPORT_CONTENT_URL) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.settings_report_content))
        }
    }
}

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 10.dp))
}

private fun movementLabelRes(m: MovementType): Int = when (m) {
    MovementType.ENTERED -> R.string.movement_entered
    MovementType.EXITED -> R.string.movement_exited
}
