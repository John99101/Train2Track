package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.model.GERMAN_CITIES
import com.mvvtracker.app.data.model.reportScopeFor
import com.mvvtracker.app.data.repository.ReportScope
import com.mvvtracker.app.data.station.StationBoard
import com.mvvtracker.app.data.station.StationRecents
import com.mvvtracker.app.data.station.StationRef
import com.mvvtracker.app.data.station.StationRepository
import com.mvvtracker.app.data.station.normalizeStationQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StationSearchState {
    /** Nothing typed yet (or too little): the recently opened stations are shown. */
    data object Idle : StationSearchState
    data object Loading : StationSearchState
    data class Results(val stations: List<StationRef>) : StationSearchState
    data object Error : StationSearchState
}

/**
 * The Stations tab: a search that waits until the rider stops typing for a moment,
 * so not every letter sends a request, and the recently opened stations.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class StationsViewModel @Inject constructor(
    private val repository: StationRepository,
    private val recents: StationRecents
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val cityId = MutableStateFlow(GERMAN_CITIES.first().id)

    val text: StateFlow<String> = query.asStateFlow()
    val recent: StateFlow<List<StationRef>> = recents.recent

    val search: StateFlow<StationSearchState> =
        combine(query.debounce(SEARCH_PAUSE_MS), cityId) { q, city -> normalizeStationQuery(q) to city }
            .distinctUntilChanged()
            .flatMapLatest { (q, city) ->
                flow {
                    if (q == null) {
                        emit(StationSearchState.Idle)
                        return@flow
                    }
                    emit(StationSearchState.Loading)
                    emit(
                        try {
                            StationSearchState.Results(repository.search(city, q))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            StationSearchState.Error
                        }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StationSearchState.Idle)

    fun setText(value: String) { query.value = value.take(40) }

    fun setCity(id: String) { cityId.value = id }

    private companion object {
        const val SEARCH_PAUSE_MS = 400L
    }
}

sealed interface StationBoardState {
    data object Loading : StationBoardState
    data class Loaded(val board: StationBoard) : StationBoardState
    data object Error : StationBoardState
}

/**
 * One station's departures. Loaded when the screen opens and again every
 * minute while it is on screen (the server keeps them 30 seconds for everybody);
 * nothing runs once the rider leaves it.
 */
@HiltViewModel
class StationBoardViewModel @Inject constructor(
    private val repository: StationRepository,
    private val recents: StationRecents,
    private val reportScope: ReportScope
) : ViewModel() {
    private val _state = MutableStateFlow<StationBoardState>(StationBoardState.Loading)
    val state: StateFlow<StationBoardState> = _state.asStateFlow()

    private var station: StationRef? = null
    private var refresher: Job? = null

    fun open(ref: StationRef) {
        if (station == ref) return
        station = ref
        _state.value = StationBoardState.Loading
        recents.opened(ref)
        // Reports and occupancy for the departures: the station's city.
        GERMAN_CITIES.firstOrNull { it.id == ref.cityId }?.let { reportScope.show(reportScopeFor(it)) }
    }

    /** While the screen is on screen: load now (unless fresh) and then every minute. */
    fun resume() {
        val ref = station ?: return
        refresher?.cancel()
        refresher = viewModelScope.launch {
            while (isActive) {
                val loaded = (_state.value as? StationBoardState.Loaded)?.board?.loadedAtMs ?: 0L
                val wait = REFRESH_MS - (System.currentTimeMillis() - loaded)
                if (wait > 0) delay(wait)
                // Failed (no connection): try again a little later, not at once.
                if (!load(ref)) delay(RETRY_MS)
            }
        }
    }

    fun pause() {
        refresher?.cancel()
        refresher = null
    }

    fun retry() { station?.let { ref -> viewModelScope.launch { load(ref) } } }

    /** true when the board came; on failure the last board stays on screen. */
    private suspend fun load(ref: StationRef): Boolean = try {
        _state.value = StationBoardState.Loaded(repository.board(ref))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (_state.value !is StationBoardState.Loaded) _state.value = StationBoardState.Error
        false
    }

    private companion object {
        const val REFRESH_MS = 60_000L
        const val RETRY_MS = 30_000L
    }
}
