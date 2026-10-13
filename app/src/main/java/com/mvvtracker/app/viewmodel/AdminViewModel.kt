package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.mvvtracker.app.data.model.UserRole
import com.mvvtracker.app.data.repository.AdminBackend
import com.mvvtracker.app.data.repository.ControlStats
import com.mvvtracker.app.data.repository.LiveStats
import com.mvvtracker.app.data.repository.PresenceRepository
import com.mvvtracker.app.data.repository.TripReports
import com.mvvtracker.app.data.repository.UserReportBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Backs the admin console: moderate reported trains (verify, dismiss, restore),
 * see how many devices are online, and read the inspection statistics.
 *
 * The role comes from the `admin` custom claim on the Firebase ID token (set by
 * the server for the ids listed in functions/lib/admins.js). Hiding the console from riders is only the UX:
 * every admin Cloud Function re-checks the claim on the server.
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val admin: AdminBackend,
    reportBackend: UserReportBackend,
    presence: PresenceRepository
) : ViewModel() {

    sealed interface StatsState {
        data object Idle : StatsState
        data object Loading : StatsState
        data class Loaded(val stats: ControlStats) : StatsState
        data object Failed : StatsState
    }

    private val _currentUserRole = MutableStateFlow(UserRole.RIDER)
    val currentUserRole: StateFlow<UserRole> = _currentUserRole.asStateFlow()

    /** Every train with live reports (also dismissed / spam blocked ones), newest first. */
    val reportedTrips: StateFlow<List<TripReports>> = reportBackend.observeAllReportedTrips()
        .map { trips -> trips.values.sortedByDescending { it.latestMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val liveStats: StateFlow<LiveStats?> = presence.observeLiveStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _stats = MutableStateFlow<StatsState>(StatsState.Idle)
    val stats: StateFlow<StatsState> = _stats.asStateFlow()

    /** Last failed action's message (shown once in the console). */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _routesRebuilding = MutableStateFlow(false)
    val routesRebuilding: StateFlow<Boolean> = _routesRebuilding.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { refreshRole(force = false) }

    init {
        auth.addAuthStateListener(authListener)
        // Once per session from the server, so a freshly granted claim shows up.
        refreshRole(force = true)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
    }

    private fun refreshRole(force: Boolean) {
        viewModelScope.launch {
            val user = auth.currentUser
            val isAdmin = user != null && try {
                user.getIdToken(force).await().claims["admin"] == true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _currentUserRole.value = if (isAdmin) UserRole.APP_MANAGER else UserRole.RIDER
        }
    }

    private fun adminAction(block: suspend () -> Unit) {
        if (_currentUserRole.value != UserRole.APP_MANAGER) return
        viewModelScope.launch {
            _actionError.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun moderate(tripId: String, moderation: AdminBackend.Moderation) =
        adminAction { admin.moderateTrip(tripId, moderation) }

    fun loadStats() = adminAction {
        _stats.value = StatsState.Loading
        _stats.value = try {
            StatsState.Loaded(admin.controlStats())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StatsState.Failed
        }
    }

    fun rebuildRoutes() = adminAction {
        _routesRebuilding.value = true
        try {
            admin.rebuildRoutes()
        } finally {
            _routesRebuilding.value = false
        }
    }
}
