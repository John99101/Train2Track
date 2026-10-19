package com.mvvtracker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvvtracker.app.R
import com.mvvtracker.app.data.model.UserRole
import com.mvvtracker.app.data.repository.AdminBackend
import com.mvvtracker.app.data.repository.ControlStats
import com.mvvtracker.app.data.repository.LiveStats
import com.mvvtracker.app.data.repository.TripReports
import com.mvvtracker.app.viewmodel.AdminViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Admin-only console: who is online, which trains are reported right now (verify,
 * dismiss false reports, restore), and the inspection statistics. A rider
 * never sees this tab (see AppNavigation), and every action is re-checked on the
 * server against the caller's admin claim.
 */
@Composable
fun AdminScreen(adminViewModel: AdminViewModel = hiltViewModel()) {
    val role by adminViewModel.currentUserRole.collectAsStateWithLifecycle()

    if (role != UserRole.APP_MANAGER) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(stringResource(R.string.admin_restricted_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.admin_restricted_body))
        }
        return
    }

    val trips by adminViewModel.reportedTrips.collectAsStateWithLifecycle()
    val live by adminViewModel.liveStats.collectAsStateWithLifecycle()
    val stats by adminViewModel.stats.collectAsStateWithLifecycle()
    val actionError by adminViewModel.actionError.collectAsStateWithLifecycle()
    val rebuilding by adminViewModel.routesRebuilding.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.admin_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.admin_sub), style = MaterialTheme.typography.bodyMedium)
        }
        item { LiveUsersCard(live) }
        actionError?.let { msg ->
            item {
                Text(
                    stringResource(R.string.admin_action_failed, msg),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Text(
                stringResource(R.string.admin_reported_trains),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (trips.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.admin_no_reports),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(trips, key = { it.tripId }) { trip ->
            ReportedTripCard(
                trip = trip,
                onModerate = { adminViewModel.moderate(trip.tripId, it) }
            )
        }
        item {
            StatsSection(stats = stats, onLoad = adminViewModel::loadStats)
        }
        item {
            OutlinedButton(
                onClick = adminViewModel::rebuildRoutes,
                enabled = !rebuilding,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(if (rebuilding) R.string.admin_routes_rebuilt else R.string.admin_routes_rebuild))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LiveUsersCard(live: LiveStats?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (live == null) {
                Text(stringResource(R.string.admin_active_users_unknown), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    pluralStringResource(R.plurals.admin_active_users, live.activeUsers, live.activeUsers),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.admin_active_users_split, live.android, live.ios),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportedTripCard(
    trip: TripReports,
    onModerate: (AdminBackend.Moderation) -> Unit
) {
    val title = listOfNotNull(trip.line, trip.station).joinToString(" · ").ifEmpty { trip.tripId }
    val dismissed = trip.dismissed > 0 && trip.onBoard == 0
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (dismissed) MaterialTheme.colorScheme.surfaceVariant
                             else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.admin_report_counts, trip.onBoard, trip.exits),
                style = MaterialTheme.typography.bodyMedium
            )
            val states = buildList {
                if (trip.verified) add(stringResource(R.string.admin_state_verified))
                if (trip.dismissed > 0) add(stringResource(R.string.admin_state_dismissed))
                if (trip.throttled > 0) add(stringResource(R.string.admin_state_throttled, trip.throttled))
            }
            if (states.isNotEmpty()) {
                Text(
                    states.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { onModerate(AdminBackend.Moderation.VERIFY) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_verify))
                }
                OutlinedButton(onClick = { onModerate(AdminBackend.Moderation.DISMISS) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_dismiss))
                }
                TextButton(onClick = { onModerate(AdminBackend.Moderation.RESTORE) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.admin_restore))
                }
            }
        }
    }
}

@Composable
private fun StatsSection(stats: AdminViewModel.StatsState, onLoad: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(R.string.admin_stats_title), style = MaterialTheme.typography.titleMedium)
        when (stats) {
            AdminViewModel.StatsState.Idle, AdminViewModel.StatsState.Failed -> {
                if (stats == AdminViewModel.StatsState.Failed) {
                    Text(stringResource(R.string.admin_stats_failed), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onLoad, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.admin_stats_load))
                }
            }
            AdminViewModel.StatsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.admin_stats_loading))
            }
            is AdminViewModel.StatsState.Loaded -> StatsContent(stats.stats)
        }
    }
}

@Composable
private fun StatsContent(s: ControlStats) {
    Text(stringResource(R.string.admin_stats_total, s.total, s.trips), style = MaterialTheme.typography.bodyMedium)

    StatsHeading(R.string.admin_stats_by_line)
    val lineMax = s.byLine.maxOfOrNull { it.second } ?: 0
    s.byLine.take(10).forEach { (line, n) -> BarRow(line, n, lineMax) }

    StatsHeading(R.string.admin_stats_by_weekday)
    val dayMax = s.byWeekday.maxOrNull() ?: 0
    val locale = LocalConfiguration.current.locales[0]
    s.byWeekday.forEachIndexed { i, n ->
        BarRow(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, locale), n, dayMax)
    }

    StatsHeading(R.string.admin_stats_by_hour)
    HourChart(s.byHour)

    StatsHeading(R.string.admin_stats_by_station)
    val stationMax = s.byStation.maxOfOrNull { it.second } ?: 0
    s.byStation.take(10).forEach { (station, n) -> BarRow(station, n, stationMax) }
}

@Composable
private fun StatsHeading(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** A label, a proportional bar and the count. */
@Composable
private fun BarRow(label: String, value: Int, max: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.width(110.dp))
        Box(modifier = Modifier.weight(1f).height(10.dp)) {
            val fraction = if (max > 0) value.toFloat() / max else 0f
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(44.dp).padding(start = 6.dp)
        )
    }
}

/** 24 small columns, one per hour of the day (Europe/Berlin). */
@Composable
private fun HourChart(byHour: List<Int>) {
    val max = byHour.maxOrNull()?.takeIf { it > 0 } ?: 1
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        byHour.forEach { n ->
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.02f))
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0", "6", "12", "18", "23").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
