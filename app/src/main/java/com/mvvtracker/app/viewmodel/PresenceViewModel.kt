package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import com.mvvtracker.app.data.repository.PresenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the UI the "app is open" heartbeat for the active users count. */
@HiltViewModel
class PresenceViewModel @Inject constructor(
    private val presence: PresenceRepository
) : ViewModel() {
    /** Runs until cancelled; tie it to the app being in the foreground. */
    suspend fun heartbeat() = presence.heartbeatLoop()
}
