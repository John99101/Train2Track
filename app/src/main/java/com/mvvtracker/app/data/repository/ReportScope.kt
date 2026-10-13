package com.mvvtracker.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which report cities the app is showing right now (see reportScopeFor in
 * GermanCity.kt). The screen that is on (Feed or Map) sets it; the report and
 * delay vote listeners in [UserReportBackend] follow it, so a phone only loads the
 * reports of the cities it shows instead of every report in Germany.
 */
@Singleton
class ReportScope @Inject constructor() {
    private val _cities = MutableStateFlow<Set<String>>(emptySet())

    /** Empty until the first screen is shown. */
    val cities: StateFlow<Set<String>> = _cities.asStateFlow()

    fun show(cities: Set<String>) {
        _cities.value = cities
    }
}
