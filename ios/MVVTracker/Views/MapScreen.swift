import SwiftUI
import MapKit
import MVVTrackerCore

/// The Map tab: an Apple map centred on the selected city (from the user's
/// location, or whatever they picked in the Feed) showing every vehicle gliding
/// along its track. Tapping a train opens the Report screen for it. An on-map
/// filter (top right) toggles which modes are shown. Port of `MapScreen.kt`
/// (Apple Maps instead of OpenStreetMap).
struct MapScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var citySelection: CitySelection
    @EnvironmentObject private var consent: ConsentStore
    @StateObject private var vm = LiveMapViewModel()
    @StateObject private var reports = ReportCountsViewModel()
    @State private var selected: TrainPosition?
    // On-map mode filter: rail on by default; buses & trams are opt-in (they are
    // numerous and were the main map performance cost). Kept in the app wide
    // FilterStore (and on the device), so it survives leaving the Map and restarts.
    @ObservedObject private var filterStore = FilterStore.shared

    private var selectedModes: Set<MapMode> {
        guard let names = filterStore.mapModes else { return [.sbahn, .ubahn, .rail] }
        return Set(MapMode.allCases.filter { names.contains($0.rawValue) })
    }

    /// Trains after the on-map mode filter (all shown when every category is on).
    private var shownTrains: [TrainPosition] {
        if selectedModes.count == MapMode.allCases.count { return vm.trains }
        let modes = selectedModes.reduce(into: Set<String>()) { $0.formUnion($1.motisModes) }
        return vm.trains.filter { modes.contains($0.mode) }
    }

    /// The routes the map wants to draw (only for the lines actually shown).
    private var shownRouteKeys: Set<String> {
        Set(shownTrains.map { routeKey(mode: $0.mode, label: $0.label) })
    }

    var body: some View {
        NavigationStack {
            LiveTrainMapView(
                trains: shownTrains,
                routes: vm.routes,
                flaggedTripIds: reports.flaggedTripIds,
                showUserLocation: consent.preciseLocationConsent,
                center: CLLocationCoordinate2D(latitude: citySelection.city.latitude,
                                               longitude: citySelection.city.longitude),
                centerToken: citySelection.city.id,
                onBoundsChange: { n, s, e, w in vm.updateBounds(north: n, south: s, east: e, west: w) },
                onTrainTap: { selected = $0 }
            )
            .ignoresSafeArea(edges: .bottom)
            .overlay(alignment: .topLeading) { statusBanner }
            .overlay(alignment: .topTrailing) { modeFilterButton }
            .navigationTitle(loc.t("nav_map"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                vm.onAppear()
                // Only the reports of what the map shows (set once the area is known).
                reports.start(cities: vm.reportCities)
                // Kick off the fetch right away for the city (the map refines the
                // bounds once it has laid out).
                let c = citySelection.city
                vm.updateBounds(north: c.latitude + 0.10, south: c.latitude - 0.10,
                                east: c.longitude + 0.16, west: c.longitude - 0.16)
            }
            .onDisappear { vm.onDisappear(); reports.stop() }
            // Fetch full routes only for the lines currently shown (map driven).
            .onChange(of: shownRouteKeys) { _, keys in vm.ensureRoutes(keys) }
            .onChange(of: vm.reportCities) { _, cities in reports.start(cities: cities) }
            .sheet(item: $selected) { train in
                ReportScreen(train: train, ticketURL: citySelection.city.ticketURL,
                             reportCity: vm.reportCity(for: train))
            }
        }
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
            .padding(12)
    }

    private var statusText: String {
        if vm.loadError && shownTrains.isEmpty { return loc.t("feed_unavailable") }
        if shownTrains.isEmpty { return loc.t("map_loading") }
        // Our server is unreachable or serves an older snapshot.
        if vm.dataDelayed { return loc.t("map_trains_delayed", shownTrains.count) }
        return loc.t("map_trains_live", shownTrains.count)
    }

    /// Top-right filter: toggle which modes appear on the map. The menu stays open
    /// while ticking several modes, like Android's checkbox menu.
    private var modeFilterButton: some View {
        Menu {
            ForEach(MapMode.allCases) { m in
                Toggle(isOn: Binding(get: { selectedModes.contains(m) },
                                     set: { on in
                                         var modes = selectedModes
                                         if on { modes.insert(m) } else { modes.remove(m) }
                                         filterStore.setMapModes(Set(modes.map(\.rawValue)))
                                     })) {
                    Text(loc.t(m.labelKey))
                }
            }
        } label: {
            FilterLinesIcon()
                .frame(width: 20, height: 18)
                .padding(9)
                .background(.ultraThinMaterial, in: Circle())
                .accessibilityLabel(Text(loc.t("feed_filter")))
        }
        .menuActionDismissBehavior(.disabled)
        .tint(.primary)
        .padding(12)
    }
}

/// Map mode categories the on-map filter can toggle (Phase 3).
private enum MapMode: String, CaseIterable, Identifiable {
    case sbahn, ubahn, tram, bus, rail
    var id: String { rawValue }

    var motisModes: Set<String> {
        switch self {
        case .sbahn: return ["METRO"]
        case .ubahn: return ["SUBWAY"]
        case .tram: return ["TRAM"]
        case .bus: return ["BUS"]
        case .rail: return ["REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL"]
        }
    }

    var labelKey: String {
        switch self {
        case .sbahn: return "label_sbahn"
        case .ubahn: return "label_ubahn"
        case .tram: return "label_tram"
        case .bus: return "label_bus"
        case .rail: return "map_other_rail"
        }
    }
}
