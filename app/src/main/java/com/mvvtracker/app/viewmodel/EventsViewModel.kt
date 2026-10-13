package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.model.EventSource
import com.mvvtracker.app.data.model.EventStatus
import com.mvvtracker.app.data.model.SBahnEvent
import com.mvvtracker.app.data.repository.EventRepository
import com.mvvtracker.app.data.repository.TripReports
import com.mvvtracker.app.data.repository.UserReportBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventsViewModel @Inject constructor(
    private val repository: EventRepository,
    private val reportBackend: UserReportBackend
) : ViewModel() {

    val activeEvents: StateFlow<List<SBahnEvent>> = repository.observeActiveEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live report state per train, from all devices (Firestore): count, time and station. */
    val reportedTrips: StateFlow<Map<String, TripReports>> = reportBackend.observeReportedTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Live count of active reports per trainId, from all devices (Firestore). */
    val remoteReportCounts: StateFlow<Map<String, Int>> = reportedTrips
        .map { trips -> trips.mapValues { it.value.flagged } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Trains flagged with a "!" in the Feed and on the Map: those reported across
     * all devices (Firestore, after the rules in ReportAggregation), plus this
     * device's own recent report for instant feedback before it round-trips.
     */
    val flaggedTripIds: StateFlow<Set<String>> =
        combine(activeEvents, remoteReportCounts) { events, remoteCounts ->
            val cutoff = System.currentTimeMillis() - EventRepository.REPORT_TTL_MILLIS
            val local = events
                .filter {
                    it.source == EventSource.USER_REPORT &&
                        it.status == EventStatus.ACTIVE &&
                        it.startedAtEpochMillis >= cutoff
                }
                .mapNotNull { it.tripId }
                .toSet()
            val remote = remoteCounts.filterValues { it >= MIN_CORROBORATIONS }.keys
            local + remote
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Live crowd delay tally per trip (Phase 6 / Option 2): tripId -> (confirm,
     * onTime). Lets the Feed show "N riders confirm this is delayed".
     */
    val delayVotes: StateFlow<Map<String, Pair<Int, Int>>> = reportBackend.observeDelayVotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Live nationwide disruptions (Phase 6 / Option 2b), read from the `disruptions`
     * collection the Cloud Function maintains — a few KB per device, not the 35 MB feed.
     */
    val disruptions: StateFlow<List<com.mvvtracker.app.data.model.Disruption>> =
        repository.observeDisruptions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.expireStaleReports()
            // Disruptions stream in from the `disruptions` collection (filled by the
            // refreshDisruptions Cloud Function) — nothing to fetch on the device.
        }
    }


    private companion object {
        // How many independent riders must report a train before it is flagged with "!".
        // 1 = instant flagging; raise to 2+ to require corroboration.
        const val MIN_CORROBORATIONS = 1
    }
}
