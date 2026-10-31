import SwiftUI
import MapKit
import MVVTrackerCore

/// The Map tab: an Apple map centred on the selected city (from the user's
/// location, or whatever they picked in the Feed) showing every vehicle gliding
/// along its track. Tapping a train opens the Report screen for it. A row of
/// buttons along the top toggles which modes are shown; highlighted ones are on
/// (S-Bahn and U-Bahn until the user picks). Below it the "Stations" button puts a
/// dot on every station of the highlighted modes; a tap on a dot opens the
/// station's departures. Port of `MapScreen.kt` (Apple Maps instead of
/// OpenStreetMap).
struct MapScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var citySelection: CitySelection
    @EnvironmentObject private var consent: ConsentStore
    @StateObject private var vm = LiveMapViewModel()
    @StateObject private var reports = ReportCountsViewModel()
    @State private var selected: TrainPosition?
    /// A station opened from its dot.
    @State private var openStation: StationRef?
    /// The map's width in degrees of longitude (which station dots the zoom allows).
    @State private var longitudeSpan = 0.14
    // Mode filter: S-Bahn and U-Bahn until the user picks; buses and trams are
    // opt-in (they are numerous and were the main map performance cost). Kept in the
    // app wide FilterStore (and on the device), so it survives leaving the Map and restarts.
    @ObservedObject private var filterStore = FilterStore.shared

    private var selectedModes: Set<MapMode> {
        let names = filterStore.mapModes ?? FilterStore.defaultModes
        return Set(MapMode.allCases.filter { names.contains($0.rawValue) })
    }

    /// The MOTIS modes on the map, nil when every category is on.
    private var shownModes: Set<String>? {
        if selectedModes.count == MapMode.allCases.count { return nil }
        return selectedModes.reduce(into: Set<String>()) { $0.formUnion($1.motisModes) }
    }

    /// Trains after the on-map mode filter (all shown when every category is on).
    private var shownTrains: [TrainPosition] {
        guard let modes = shownModes else { return vm.trains }
        return vm.trains.filter { modes.contains($0.mode) }
    }

    /// The map buttons whose stations get a dot; 0 while the "Stations" button is off.
    private var stationBits: Int {
        guard filterStore.mapStations else { return 0 }
        return selectedModes.reduce(0) { $0 | $1.stationBit }
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
                onBoundsChange: { n, s, e, w in
                    longitudeSpan = e - w
                    vm.updateBounds(north: n, south: s, east: e, west: w)
                },
                onTrainTap: { selected = $0 },
                stations: vm.stations.stations,
                stationBits: stationBits,
                onStationTap: { station in
                    guard let city = vm.stations.cityId else { return }
                    openStation = StationRef(id: station.id, name: station.name, cityId: city,
                                             modes: MapGroups.modes(station.bits))
                }
            )
            .ignoresSafeArea(edges: .bottom)
            .overlay(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    modeChips
                    stationsButton
                    statusBanner
                    if let note = stationNote { noteBanner(note) }
                }
            }
            .navigationTitle(loc.t("nav_map"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                // Download only the vehicles of these modes (rail alone by default).
                vm.showModes(shownModes)
                vm.showStations(filterStore.mapStations)
                vm.onAppear()
                // Only the reports of what the map shows (set once the area is known).
                reports.start(cities: vm.reportCities)
                // Kick off the fetch right away for the city (the map refines the
                // bounds once it has laid out). Not when coming back from a station or
                // another tab: the map still shows its area and reports no change, so
                // the estimate would hide the vehicles outside the middle and switch a
                // city looked at back to the selected one.
                if !vm.hasViewport {
                    let c = citySelection.city
                    vm.updateBounds(north: c.latitude + 0.10, south: c.latitude - 0.10,
                                    east: c.longitude + 0.16, west: c.longitude - 0.16)
                }
            }
            .onDisappear { vm.onDisappear(); reports.stop() }
            // Fetch full routes only for the lines currently shown (map driven).
            .onChange(of: shownRouteKeys) { _, keys in vm.ensureRoutes(keys) }
            .onChange(of: shownModes) { _, modes in vm.showModes(modes) }
            .onChange(of: filterStore.mapStations) { _, on in vm.showStations(on) }
            .onChange(of: vm.reportCities) { _, cities in reports.start(cities: cities) }
            .sheet(item: $selected) { train in
                ReportScreen(train: train, ticketURL: citySelection.city.ticketURL,
                             reportCity: vm.reportCity(for: train), reports: reports)
            }
            .navigationDestination(item: $openStation) { StationScreen(station: $0) }
        }
    }

    /// The "Stations" button is on, but no dot can be shown: why.
    private var stationNote: String? {
        if stationBits == 0 { return nil }
        if vm.stations.loaded && vm.stations.stations.isEmpty { return loc.t("map_stations_unavailable") }
        if stationBits & ~stationMask(longitudeSpan: longitudeSpan) != 0 { return loc.t("map_stations_zoom") }
        return nil
    }

    private func noteBanner(_ text: String) -> some View {
        Text(text)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 12)
            .padding(.top, 6)
    }

    /// Under the filter buttons: the stations of the highlighted modes, as dots.
    private var stationsButton: some View {
        let on = filterStore.mapStations
        return Button {
            filterStore.setMapStations(!on)
        } label: {
            HStack(spacing: 6) {
                // The dot as it looks on the map, faded while off.
                Circle()
                    .fill(Color.white)
                    .overlay(Circle().strokeBorder(Color(red: 60 / 255, green: 60 / 255, blue: 60 / 255), lineWidth: 2))
                    .frame(width: 12, height: 12)
                    .opacity(on ? 1 : 0.4)
                Text(loc.t("map_stations"))
                    .font(.subheadline.weight(on ? .semibold : .regular))
                    .foregroundStyle(on ? Color.primary : Color.secondary)
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(on ? AnyShapeStyle(Color.accentColor.opacity(0.22)) : AnyShapeStyle(.regularMaterial),
                        in: Capsule())
            .overlay(Capsule().strokeBorder(on ? Color.accentColor : Color.secondary.opacity(0.35),
                                            lineWidth: on ? 1.5 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 12)
            .padding(.top, 4)
    }

    private var statusText: String {
        if vm.loadError && shownTrains.isEmpty { return loc.t("feed_unavailable") }
        if shownTrains.isEmpty { return loc.t("map_loading") }
        // Our server is unreachable or serves an older snapshot.
        if vm.dataDelayed { return loc.plural("map_trains_delayed", shownTrains.count) }
        return loc.plural("map_trains_live", shownTrains.count)
    }

    /// Filter buttons along the top: a highlighted button shows its vehicles, a tap
    /// switches it on or off. Scrolls sideways on narrow screens. Like Android's chips.
    private var modeChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(MapMode.allCases) { m in
                    let on = selectedModes.contains(m)
                    Button {
                        var modes = selectedModes
                        if on { modes.remove(m) } else { modes.insert(m) }
                        filterStore.setMapModes(Set(modes.map(\.rawValue)))
                    } label: {
                        HStack(spacing: 6) {
                            // A faded symbol on a button that is off, so the state does
                            // not rest on colour alone.
                            ModeSymbol(mode: m.symbolMode, size: 18).opacity(on ? 1 : 0.4)
                            Text(loc.t(m.labelKey))
                                .font(.subheadline.weight(on ? .semibold : .regular))
                                .foregroundStyle(on ? Color.primary : Color.secondary)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(on ? AnyShapeStyle(Color.accentColor.opacity(0.22)) : AnyShapeStyle(.regularMaterial),
                                    in: Capsule())
                        .overlay(Capsule().strokeBorder(on ? Color.accentColor : Color.secondary.opacity(0.35),
                                                        lineWidth: on ? 1.5 : 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }
}

/// Map mode categories the filter buttons toggle.
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

    /// The stations the button shows when the "Stations" button is on.
    var stationBit: Int {
        switch self {
        case .sbahn: return MapGroups.sbahn
        case .ubahn: return MapGroups.ubahn
        case .tram: return MapGroups.tram
        case .bus: return MapGroups.bus
        case .rail: return MapGroups.rail
        }
    }

    /// The mode whose symbol the button shows.
    var symbolMode: String {
        switch self {
        case .sbahn: return "METRO"
        case .ubahn: return "SUBWAY"
        case .tram: return "TRAM"
        case .bus: return "BUS"
        case .rail: return "REGIONAL_RAIL"
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
