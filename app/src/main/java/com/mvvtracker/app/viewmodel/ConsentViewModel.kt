package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.repository.AccountRepository
import com.mvvtracker.app.privacy.ConsentManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val consentManager: ConsentManager,
    private val accountRepository: AccountRepository
) : ViewModel() {

    enum class DeleteState { Idle, Running, Done, Failed }

    private val _deleteState = MutableStateFlow(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    /** null = still loading; false = show consent; true = already accepted. */
    val hasSeenConsent: StateFlow<Boolean?> = consentManager.hasSeenConsentScreen
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** null = still loading; false = show onboarding; true = already seen. */
    val hasSeenTutorial: StateFlow<Boolean?> = consentManager.hasSeenTutorial
        .map { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setTutorialSeen() {
        viewModelScope.launch { consentManager.setTutorialSeen() }
    }

    fun saveConsent(analytics: Boolean, preciseLocation: Boolean) {
        viewModelScope.launch {
            consentManager.setAnalyticsConsent(analytics)
            consentManager.setPreciseLocationConsent(preciseLocation)
            consentManager.setConsentScreenSeen()
        }
    }

    /** "Delete my data" - on the server and on the device, see [AccountRepository]. */
    fun deleteAllMyData() {
        if (_deleteState.value == DeleteState.Running) return
        viewModelScope.launch {
            _deleteState.value = DeleteState.Running
            _deleteState.value =
                if (accountRepository.deleteAllMyData()) DeleteState.Done else DeleteState.Failed
        }
    }
}
