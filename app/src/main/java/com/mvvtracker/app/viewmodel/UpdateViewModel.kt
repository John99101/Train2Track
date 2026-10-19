package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mvvtracker.app.data.remote.MinVersionCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Asks once per start whether this version may still be used (see [MinVersionCheck]). */
@HiltViewModel
class UpdateViewModel @Inject constructor(check: MinVersionCheck) : ViewModel() {
    private val _updateRequired = MutableStateFlow(false)
    val updateRequired: StateFlow<Boolean> = _updateRequired.asStateFlow()

    init {
        viewModelScope.launch { _updateRequired.value = check.updateRequired() }
    }
}
