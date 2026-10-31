package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.model.CITY_OTHER
import com.mvvtracker.app.data.model.EventSeverity
import com.mvvtracker.app.data.model.MovementType
import com.mvvtracker.app.data.model.UserEventReport
import com.mvvtracker.app.data.repository.EventRepository
import com.mvvtracker.app.privacy.ConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ReportSubmitState {
    data object Idle : ReportSubmitState
    data object Submitting : ReportSubmitState
    /** [movement] is what was sent, so the confirmation fits the action. */
    data class Success(val movement: MovementType) : ReportSubmitState
    data class Error(val message: String) : ReportSubmitState
}

/**
 * Backs the report flow for one specific train (reached by tapping it).
 *
 *  - [MovementType.ENTERED] starts/keeps the event on that train: any number of
 *    riders entering just corroborates the same train (dedup per rider).
 *  - [MovementType.EXITED] ends this rider's report for the train; once everyone
 *    who entered has exited, the train's "!" clears.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val repository: EventRepository,
    private val consentManager: ConsentManager
) : ViewModel() {

    private val _submitState = MutableStateFlow<ReportSubmitState>(ReportSubmitState.Idle)
    val submitState: StateFlow<ReportSubmitState> = _submitState.asStateFlow()

    // Separate state for the delay confirm/deny votes (Phase 6 / Option 2), so a
    // delay vote never disturbs the inspector-report submit state.
    private val _delayVoteState = MutableStateFlow<DelayVoteState>(DelayVoteState.Idle)
    val delayVoteState: StateFlow<DelayVoteState> = _delayVoteState.asStateFlow()

    private val _occupancyVoteState = MutableStateFlow<OccupancyVoteState>(OccupancyVoteState.Idle)
    val occupancyVoteState: StateFlow<OccupancyVoteState> = _occupancyVoteState.asStateFlow()

    fun submit(
        tripId: String?,
        line: String?,
        station: String?,
        movement: MovementType,
        city: String?
    ) {
        if (tripId.isNullOrBlank()) {
            _submitState.value = ReportSubmitState.Error("Open a train from the map or feed first.")
            return
        }
        // One at a time: a double tap would add a second row to the statistics.
        if (_submitState.value is ReportSubmitState.Submitting) return
        // The city the train was seen in; every app only loads its own cities' reports.
        val reportCity = city?.takeIf { it.isNotBlank() } ?: CITY_OTHER
        viewModelScope.launch {
            _submitState.value = ReportSubmitState.Submitting
            try {
                when (movement) {
                    MovementType.ENTERED -> {
                        val pseudoId = consentManager.pseudonymousId()
                        repository.submitUserReport(
                            UserEventReport(
                                lines = listOfNotNull(line?.takeIf { it.isNotBlank() }),
                                stationHint = station,
                                description = "Entered at ${station ?: "?"}",
                                severity = EventSeverity.INFO,
                                latitude = null,
                                longitude = null,
                                tripId = tripId
                            ),
                            pseudonymousUserId = pseudoId,
                            city = reportCity
                        )
                    }
                    MovementType.EXITED -> repository.endUserReport(tripId, reportCity)
                }
                _submitState.value = ReportSubmitState.Success(movement)
            } catch (e: Exception) {
                _submitState.value = ReportSubmitState.Error(e.message ?: "Could not submit report.")
            }
        }
    }

    /**
     * Records a delay vote for the train (Phase 6 / Option 2): [confirm] = "yes,
     * it's late", else "no, on time". Independent of the inspector report.
     */
    fun voteOccupancy(tripId: String?, level: Int, city: String?) {
        if (tripId.isNullOrBlank()) return
        val reportCity = city?.takeIf { it.isNotBlank() } ?: CITY_OTHER
        viewModelScope.launch {
            _occupancyVoteState.value = OccupancyVoteState.Submitting(level)
            _occupancyVoteState.value = try {
                repository.submitOccupancyVote(tripId, level, reportCity)
                OccupancyVoteState.Done(level)
            } catch (e: Exception) {
                OccupancyVoteState.Error(e.message ?: "Could not record your answer.")
            }
        }
    }

    fun voteDelay(tripId: String?, confirm: Boolean, city: String?) {
        if (tripId.isNullOrBlank()) return
        val reportCity = city?.takeIf { it.isNotBlank() } ?: CITY_OTHER
        viewModelScope.launch {
            _delayVoteState.value = DelayVoteState.Submitting
            _delayVoteState.value = try {
                repository.submitDelayVote(tripId, confirm, reportCity)
                DelayVoteState.Done(confirm)
            } catch (e: Exception) {
                DelayVoteState.Error(e.message ?: "Could not record your vote.")
            }
        }
    }
}

sealed interface OccupancyVoteState {
    data object Idle : OccupancyVoteState
    data class Submitting(val level: Int) : OccupancyVoteState
    data class Done(val level: Int) : OccupancyVoteState
    data class Error(val message: String) : OccupancyVoteState
}

sealed interface DelayVoteState {
    data object Idle : DelayVoteState
    data object Submitting : DelayVoteState
    data class Done(val confirmed: Boolean) : DelayVoteState
    data class Error(val message: String) : DelayVoteState
}
