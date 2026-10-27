package com.mvvtracker.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DepartureBoard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mvvtracker.app.R
import com.mvvtracker.app.data.model.UserRole
import com.mvvtracker.app.ui.screens.AboutScreen
import com.mvvtracker.app.ui.screens.AdminScreen
import com.mvvtracker.app.ui.screens.EventFeedScreen
import com.mvvtracker.app.ui.screens.MapScreen
import com.mvvtracker.app.ui.screens.PrivacyPolicyScreen
import com.mvvtracker.app.ui.screens.LegalNoticeScreen
import com.mvvtracker.app.ui.screens.ReportEventScreen
import com.mvvtracker.app.ui.screens.SettingsScreen
import com.mvvtracker.app.ui.screens.StationScreen
import com.mvvtracker.app.ui.screens.StationsScreen
import com.mvvtracker.app.data.station.StationRef
import com.mvvtracker.app.viewmodel.AdminViewModel
import com.mvvtracker.app.viewmodel.CitySelectionViewModel
import com.mvvtracker.app.viewmodel.PresenceViewModel
import com.mvvtracker.app.viewmodel.SettingsViewModel

private sealed class Dest(val route: String, val label: String) {
    val navTarget: String get() = route.substringBefore("?")

    data object Feed : Dest("feed", "Feed")
    data object Map : Dest("map", "Map")
    data object Stations : Dest("stations", "Stations")
    // One station's departures, opened from the Stations tab.
    data object Station : Dest("station?id={id}&name={name}&city={city}&modes={modes}", "Station")
    // Report has no tab; it's reached by tapping a train.
    data object Report : Dest("report?line={line}&trip={trip}&station={station}&mode={mode}&city={city}", "Report")
    data object Admin : Dest("admin", "Admin")
    data object Settings : Dest("settings", "Settings")
    data object Privacy : Dest("privacy", "Privacy policy")
    data object Legal : Dest("legal", "Legal notice")
    data object About : Dest("about", "Data sources and open source")
}

private fun stationRoute(s: StationRef): String =
    "station?id=${Uri.encode(s.id)}&name=${Uri.encode(s.name)}&city=${Uri.encode(s.cityId)}" +
        "&modes=${Uri.encode(s.modes.joinToString(","))}"

private fun reportRoute(line: String?, tripId: String?, station: String?, mode: String?, city: String?): String =
    "report?line=${Uri.encode(line.orEmpty())}" +
        "&trip=${Uri.encode(tripId.orEmpty())}" +
        "&station=${Uri.encode(station.orEmpty())}" +
        "&mode=${Uri.encode(mode.orEmpty())}" +
        "&city=${Uri.encode(city.orEmpty())}"

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val adminViewModel: AdminViewModel = hiltViewModel()
    val role by adminViewModel.currentUserRole.collectAsStateWithLifecycle()

    // Shared, activity-scoped so the chosen city survives switching between the
    // Feed and Map tabs (and stays in sync between them).
    val citySelectionViewModel: CitySelectionViewModel = hiltViewModel()

    // "App is open" heartbeat for the active users count, only while in the foreground.
    val presenceViewModel: PresenceViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { presenceViewModel.heartbeat() }
    }

    // Ask for coarse location once, only if the user opted into precise location.
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val locationConsent by settingsViewModel.preciseLocationConsent.collectAsStateWithLifecycle(initialValue = false)
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(locationConsent) {
        if (locationConsent &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The tab a train's report was opened from (Feed or Map), so that tab stays
    // highlighted on the report and tapping it leads back.
    var reportOrigin by rememberSaveable { mutableStateOf(Dest.Feed.route) }
    // The tab the current screen belongs to (the report, the two legal pages and
    // the data sources page have no tab of their own).
    val currentTabRoute = when (currentRoute) {
        Dest.Report.route -> reportOrigin
        Dest.Station.route -> Dest.Stations.route
        Dest.Privacy.route, Dest.Legal.route, Dest.About.route -> Dest.Settings.route
        else -> currentRoute
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = buildList {
                    add(Dest.Feed)
                    add(Dest.Map)
                    add(Dest.Stations)
                    if (role == UserRole.APP_MANAGER) add(Dest.Admin)
                    add(Dest.Settings)
                }
                items.forEach { dest ->
                    val label = stringResource(labelResFor(dest))
                    NavigationBarItem(
                        selected = currentTabRoute == dest.route,
                        onClick = {
                            if (currentTabRoute == dest.route) {
                                // Already on this tab: back to its first screen, e.g.
                                // from a train's report to the Feed or the Map.
                                if (currentRoute != dest.route) navController.popBackStack(dest.route, inclusive = false)
                            } else {
                                // Close a sub screen (a report, a legal page) first, so
                                // this tab shows its main screen when coming back to it.
                                if (currentRoute != currentTabRoute) navController.popBackStack()
                                // One instance per tab, kept with its state: without
                                // popUpTo/saveState every tap stacked a brand new screen
                                // (scroll position and search lost, old screens piling up).
                                navController.navigate(dest.navTarget) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(iconFor(dest), contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Feed.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Feed.route) {
                EventFeedScreen(
                    citySelectionViewModel = citySelectionViewModel,
                    onReportTrain = { line, tripId, station, mode, city ->
                        reportOrigin = Dest.Feed.route
                        navController.navigate(reportRoute(line, tripId, station, mode, city))
                    }
                )
            }
            composable(Dest.Map.route) {
                MapScreen(
                    citySelectionViewModel = citySelectionViewModel,
                    onTrainClick = { line, tripId, station, mode, city ->
                        reportOrigin = Dest.Map.route
                        navController.navigate(reportRoute(line, tripId, station, mode, city))
                    }
                )
            }
            composable(Dest.Stations.route) {
                StationsScreen(
                    citySelectionViewModel = citySelectionViewModel,
                    onOpen = { navController.navigate(stationRoute(it)) }
                )
            }
            composable(
                route = Dest.Station.route,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("city") { type = NavType.StringType; defaultValue = "" },
                    navArgument("modes") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                val station = StationRef(
                    id = entry.arguments?.getString("id").orEmpty(),
                    name = entry.arguments?.getString("name").orEmpty(),
                    cityId = entry.arguments?.getString("city").orEmpty(),
                    modes = entry.arguments?.getString("modes").orEmpty().split(",").filter { it.isNotBlank() }
                )
                StationScreen(
                    station = station,
                    onBack = { navController.popBackStack() },
                    onOpenTrain = { line, tripId, stationName, mode, city ->
                        reportOrigin = Dest.Stations.route
                        navController.navigate(reportRoute(line, tripId, stationName, mode, city))
                    }
                )
            }
            composable(
                route = Dest.Report.route,
                arguments = listOf(
                    navArgument("line") { type = NavType.StringType; defaultValue = "" },
                    navArgument("trip") { type = NavType.StringType; defaultValue = "" },
                    navArgument("station") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mode") { type = NavType.StringType; defaultValue = "" },
                    navArgument("city") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                ReportEventScreen(
                    citySelectionViewModel = citySelectionViewModel,
                    preselectedLine = entry.arguments?.getString("line")?.ifBlank { null },
                    tripId = entry.arguments?.getString("trip")?.ifBlank { null },
                    currentStation = entry.arguments?.getString("station")?.ifBlank { null },
                    mode = entry.arguments?.getString("mode")?.ifBlank { null },
                    city = entry.arguments?.getString("city")?.ifBlank { null },
                    onBack = { navController.popBackStack() }
                )
            }
            // Shares the activity-level instance, so the role is checked once.
            composable(Dest.Admin.route) { AdminScreen(adminViewModel) }
            composable(Dest.Settings.route) {
                SettingsScreen(
                    onOpenPrivacyPolicy = { navController.navigate(Dest.Privacy.route) },
                    onOpenLegalNotice = { navController.navigate(Dest.Legal.route) },
                    onOpenAbout = { navController.navigate(Dest.About.route) }
                )
            }
            composable(Dest.Privacy.route) { PrivacyPolicyScreen() }
            composable(Dest.Legal.route) { LegalNoticeScreen() }
            composable(Dest.About.route) { AboutScreen() }
        }
    }
}

private fun labelResFor(dest: Dest): Int = when (dest) {
    Dest.Feed -> R.string.nav_feed
    Dest.Map -> R.string.nav_map
    Dest.Stations, Dest.Station -> R.string.nav_stations
    Dest.Report -> R.string.nav_report
    Dest.Admin -> R.string.nav_admin
    Dest.Settings, Dest.Privacy, Dest.Legal, Dest.About -> R.string.nav_settings
}

private fun iconFor(dest: Dest) = when (dest) {
    Dest.Map -> Icons.Filled.Map
    Dest.Feed -> Icons.AutoMirrored.Filled.List
    Dest.Stations, Dest.Station -> Icons.Filled.DepartureBoard
    Dest.Admin -> Icons.Filled.AdminPanelSettings
    else -> Icons.Filled.Settings
}
