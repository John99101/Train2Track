import SwiftUI
import Combine
import MVVTrackerCore

/// The Stations tab: search a station of the selected city, or open one opened
/// before. Nothing is loaded until the rider types; stations are not shown on the
/// map. Port of Android's `StationsScreen`.
struct StationsScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var citySelection: CitySelection
    @StateObject private var vm = StationsViewModel()
    @ObservedObject private var recents = StationRecents.shared

    private var recentHere: [StationRef] { recents.recent.filter { $0.cityId == citySelection.city.id } }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    switch vm.state {
                    case .idle:
                        if recentHere.isEmpty {
                            Text(loc.t("stations_empty_hint")).foregroundStyle(.secondary)
                        }
                    case .loading:
                        HStack { Spacer(); ProgressView(); Spacer() }
                    case .results(let list):
                        if list.isEmpty {
                            Text(loc.t("stations_no_results", citySelection.city.name)).foregroundStyle(.secondary)
                        } else {
                            ForEach(list) { StationRow(station: $0) }
                        }
                    case .error:
                        Text(loc.t("stations_error")).foregroundStyle(.secondary)
                    }
                } header: {
                    Text(loc.t("stations_in_city", citySelection.city.name)).textCase(nil)
                }
                if vm.state == .idle, !recentHere.isEmpty {
                    Section(loc.t("stations_recent")) {
                        ForEach(recentHere) { StationRow(station: $0) }
                    }
                }
            }
            .searchable(text: $vm.text, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text(loc.t("stations_search_hint")))
            .navigationTitle(loc.t("nav_stations"))
            .navigationDestination(for: StationRef.self) { StationScreen(station: $0) }
            .onAppear { vm.cityId = citySelection.city.id }
            .onChange(of: citySelection.city.id) { _, id in vm.cityId = id }
        }
    }
}

private struct StationRow: View {
    let station: StationRef

    var body: some View {
        NavigationLink(value: station) {
            HStack(spacing: 12) {
                Image(systemName: "signpost.right.fill").foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(station.name).font(.body.weight(.semibold))
                    let symbols = station.symbolModes
                    if !symbols.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(symbols, id: \.self) { ModeSymbol(mode: $0, size: 16) }
                        }
                    }
                }
            }
        }
    }
}

/// One station: its next departures (with the "!" and the occupancy of a journey
/// when riders reported them; a tap opens the journey's report window). Refreshed
/// every minute while on screen.
/// Port of Android's `StationScreen`.
struct StationScreen: View {
    let station: StationRef

    @EnvironmentObject private var loc: LocalizationManager
    @StateObject private var vm = StationBoardViewModel()
    @StateObject private var reports = ReportCountsViewModel()
    @State private var selected: TrainPosition?
    /// Minutes until departure count down on the phone between two loads.
    @State private var nowMs = Int64(Date().timeIntervalSince1970 * 1000)
    private let tick = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    private var city: GermanCity { germanCities.first { $0.id == station.cityId } ?? germanCities[0] }

    private static let clockFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f
    }()
    static func clockText(_ ms: Int64) -> String {
        clockFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }

    var body: some View {
        List {
            switch vm.state {
            case .loading:
                HStack { Spacer(); ProgressView(); Spacer() }
            case .error:
                VStack(spacing: 8) {
                    Text(loc.t("stations_error")).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Button(loc.t("station_retry")) { vm.retry(station) }
                }
                .frame(maxWidth: .infinity)
            case .loaded(let board):
                departures(board)
            }
        }
        .navigationTitle(station.name)
        .onAppear {
            vm.start(station)
            // Reports and occupancy for the departures: the station's city.
            reports.start(cities: reportScope(for: city))
        }
        .onDisappear { vm.stop(); reports.stop() }
        .onReceive(tick) { nowMs = Int64($0.timeIntervalSince1970 * 1000) }
        .sheet(item: $selected) { train in
            ReportScreen(train: train, ticketURL: city.ticketURL, reportCity: station.cityId, reports: reports)
        }
    }

    private func departures(_ board: StationBoard) -> some View {
        // Departures gone more than half a minute ago drop out before the next load.
        let upcoming = board.departures.filter { $0.expectedAtMs >= nowMs - 30_000 }
        return Section {
            if upcoming.isEmpty {
                Text(loc.t("station_no_departures")).foregroundStyle(.secondary)
            }
            ForEach(upcoming) { dep in
                let train = trainPosition(dep, station: board.name.isEmpty ? station.name : board.name)
                Button { if train.tripId != nil { selected = train } } label: {
                    DepartureRow(departure: dep, train: train, nowMs: nowMs,
                                 flagged: dep.tripId.isEmpty ? false : reports.flaggedTripIds.contains(dep.tripId),
                                 occupancyLevel: reports.occupancy[dep.tripId]?.level)
                }
                .buttonStyle(.plain)
            }
        } header: {
            HStack {
                Text(loc.t("station_departures"))
                Spacer()
                Text(loc.t("station_updated", Self.clockText(board.loadedAtMs)))
            }
        }
    }

    /// The departure as the report window takes a train: the station as the current
    /// stop, the destination as the direction.
    private func trainPosition(_ d: Departure, station name: String) -> TrainPosition {
        TrainPosition(latitude: 0, longitude: 0, label: d.label, mode: d.mode,
                      tripId: d.tripId.isEmpty ? nil : d.tripId,
                      fromStop: name, toStop: d.destination,
                      colorHex: d.colorHex.isEmpty ? nil : d.colorHex,
                      delayMinutes: d.realtime ? d.delayMinutes : nil, isRealtime: d.realtime)
    }
}

private struct DepartureRow: View {
    @EnvironmentObject private var loc: LocalizationManager
    let departure: Departure
    let train: TrainPosition
    let nowMs: Int64
    let flagged: Bool
    let occupancyLevel: Int?

    var body: some View {
        HStack(spacing: 12) {
            Text(departure.label)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(train.displayColor.contrastingLabel)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(minWidth: 44, minHeight: 32)
                .padding(.horizontal, 4)
                .background(train.displayColor, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 3) {
                Text(departure.destination)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                    .strikethrough(departure.cancelled)
                HStack(spacing: 6) {
                    if !departure.platform.isEmpty {
                        Text(loc.t("station_platform", departure.platform)).font(.caption).foregroundStyle(.secondary)
                    }
                    if departure.cancelled {
                        Text(loc.t("station_cancelled")).font(.caption.weight(.semibold)).foregroundStyle(.red)
                    } else {
                        DelayBadge(delayMinutes: departure.realtime ? departure.delayMinutes : nil,
                                   isRealtime: departure.realtime)
                            .environmentObject(loc)
                    }
                    if let occupancyLevel {
                        OccupancyMeter(level: occupancyLevel, segmentWidth: 5, height: 7)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if flagged {
                Text("!")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 20, height: 20)
                    .background(Color(red: 0.86, green: 0.16, blue: 0.16), in: Circle())
            }
            VStack(alignment: .trailing, spacing: 2) {
                let minutes = Int((departure.expectedAtMs - nowMs) / 60_000)
                Text(minutes <= 0 ? loc.t("station_now") : loc.t("station_minutes", minutes))
                    .font(.subheadline.weight(.bold))
                Text(StationScreen.clockText(departure.plannedAtMs))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}
