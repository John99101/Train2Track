package com.mvvtracker.app.viewmodel

import androidx.lifecycle.ViewModel
import com.mvvtracker.app.data.local.FilterStore
import com.mvvtracker.app.data.model.GERMAN_CITIES
import com.mvvtracker.app.data.model.GermanCity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The city the user is currently viewing, shared across the Feed and Map tabs and
 * kept alive for the whole app session (activity-scoped) so it never reverts to
 * the default when switching tabs. The city shown last is remembered on the
 * device ([FilterStore]) and shown again at the next start.
 *
 * The user's location may preselect a city, but once the user picks one manually
 * that choice sticks for the session — later location updates no longer override it.
 */
@HiltViewModel
class CitySelectionViewModel @Inject constructor(
    private val filterStore: FilterStore
) : ViewModel() {

    private val _selectedCity = MutableStateFlow(
        GERMAN_CITIES.firstOrNull { it.id == filterStore.filters.value.cityId } ?: GERMAN_CITIES.first()
    )
    val selectedCity: StateFlow<GermanCity> = _selectedCity.asStateFlow()

    /** True once the user has explicitly chosen a city from the dropdown. */
    private var pickedManually = false

    /** The user chose a city from the dropdown: honour it and stop auto-switching. */
    fun selectManually(city: GermanCity) {
        pickedManually = true
        show(city)
    }

    /** A city derived from the user's location: apply only until they pick one. */
    fun suggestFromLocation(city: GermanCity) {
        if (!pickedManually && _selectedCity.value != city) show(city)
    }

    private fun show(city: GermanCity) {
        _selectedCity.value = city
        filterStore.setCity(city.id)
    }
}
