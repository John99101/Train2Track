import SwiftUI
import MVVTrackerCore

/// The Feed tab: pick a German city and see a live LIST of its S-Bahn, U-Bahn,
/// buses and trams. Each row shows the line badge, where it just left and where
/// it's heading, and the mode. Trains a rider has an active report on are marked
/// with a red "!" and float to the top. A skippable native ad sits at the bottom.
/// Port of `EventFeedScreen.kt`.
struct FeedScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @EnvironmentObject private var consent: ConsentStore
    @EnvironmentObject private var citySelection: CitySelection
    @Environment(\.openURL) private var openURL

    @StateObject private var live = LiveMapViewModel()
    @StateObject private var reports = ReportCountsViewModel()
    @StateObject private var disruptionsVM = DisruptionsViewModel()

    @State private var selectedTrain: TrainPosition?

    // Feed filters: modes (several at once, none = all) and/or current reports, and
    // a single line. They live in the app wide FilterStore (and on the device), so
    // they survive leaving the Feed and closing the app. The line only applies in
    // the city it was picked in. The search text is per visit.
    @ObservedObject private var filterStore = FilterStore.shared
    @State private var query = ""

    private var modeFilters: Set<FeedMode> {
        Set(FeedMode.allCases.filter { filterStore.feedModes.contains($0.key) })
    }

    private func setModeFilters(_ modes: Set<FeedMode>) {
        filterStore.setFeedModes(Set(modes.map(\.key)))
    }

    /// nil = all lines.
    private var selectedLine: String? { filterStore.feedLine(in: selectedCity.id) }

    private func setSelectedLine(_ line: String?) {
        filterStore.setFeedLine(cityId: selectedCity.id, line: line)
    }

    // Already-opened trains: dimmed in the list (no hide toggle).
    @ObservedObject private var seenStore = SeenTrainsStore.shared

    private var selectedCity: GermanCity { citySelection.city }

    /// Nationwide disruptions narrowed to those that mention the selected city.
    /// The gtfs.de alerts carry no coordinates, so we match the city name in the text.
    private var localDisruptions: [Disruption] {
        let needle = selectedCity.name.lowercased()
        return disruptionsVM.disruptions.filter { d in
            (d.title + " " + d.description + " " + d.affected.joined(separator: " "))
                .lowercased().contains(needle)
        }
    }

    /// Feed filters, combinable: any of S-Bahn / U-Bahn / Bus / Tram (shown together),
    /// optionally narrowed to the currently reported trains (most reported first).
    /// No filter selected = "All". Order matches the filter menu (Android's FeedMode).
    private enum FeedMode: Int, CaseIterable, Hashable {
        case sbahn, ubahn, bus, tram, reports
        /// The name the filter is stored under (see FilterStore).
        var key: String {
            switch self {
            case .sbahn: return "sbahn"
            case .ubahn: return "ubahn"
            case .bus: return "bus"
            case .tram: return "tram"
            case .reports: return "reports"
            }
        }
        var motisMode: String? {
            switch self {
            case .sbahn: return "METRO"
            case .ubahn: return "SUBWAY"
            case .bus: return "BUS"
            case .tram: return "TRAM"
            case .reports: return nil
            }
        }
    }

    /// True when a vehicle of `mode` passes the selected mode filters (none = all).
    private func allowsMode(_ mode: String) -> Bool {
        let modes = modeFilters.compactMap(\.motisMode)
        return modes.isEmpty || modes.contains(mode)
    }

    private var reportsOnly: Bool { modeFilters.contains(.reports) }

    /// Changing a filter jumps back to the top of the list.
    private var filterSignature: String {
        "\(modeFilters.map(\.rawValue).sorted())|\(selectedLine ?? "")|\(query)"
    }

    /// Local-transit modes shown in the Feed (rail rapid transit + buses/trams).
    private static let feedModes: Set<String> = ["METRO", "SUBWAY", "BUS", "TRAM"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                cityPicker
                filterControls
                statusLine
                if !localDisruptions.isEmpty {
                    DisruptionsBanner(disruptions: localDisruptions).environmentObject(loc)
                }
                trainList
            }
            .navigationTitle(loc.t("nav_feed"))
            .safeAreaInset(edge: .bottom) { adSlot }
            .onAppear {
                live.onAppear()
                // Only the reports of this city (and the cities overlapping it).
                reports.start(cities: reportScope(for: selectedCity))
                disruptionsVM.start()
                live.showCity(selectedCity)   // the whole city's network, no map here
            }
            .onDisappear {
                live.onDisappear()
                reports.stop()
                disruptionsVM.stop()
            }
            .onChange(of: selectedCity) { _, city in
                live.showCity(city)
                reports.start(cities: reportScope(for: city))
            }
            .sheet(item: $selectedTrain) {
                // Every train in the Feed belongs to the selected city.
                ReportScreen(train: $0, ticketURL: selectedCity.ticketURL, reportCity: selectedCity.id,
                             reports: reports)
            }
        }
    }

    // MARK: - Derived list

    /// S-Bahn + U-Bahn only, de-duplicated, flagged trains floated to the top,
    /// then by label and destination. Mirrors `suTrains` in EventFeedScreen.kt.
    private var suTrains: [TrainPosition] {
        let flagged = reports.flaggedTripIds
        let counts = reports.counts
        let q = query.trimmingCharacters(in: .whitespaces)
        var seen = Set<String>()
        let filtered = live.trains.filter { train in
            guard Self.feedModes.contains(train.mode), allowsMode(train.mode) else { return false }
            if reportsOnly {
                guard let id = train.tripId, flagged.contains(id) else { return false }
            }
            if let line = selectedLine, train.label != line { return false }
            if !q.isEmpty {
                let inLabel = train.label.localizedCaseInsensitiveContains(q)
                let inFrom = train.fromStop?.localizedCaseInsensitiveContains(q) ?? false
                let inTo = train.toStop?.localizedCaseInsensitiveContains(q) ?? false
                if !inLabel && !inFrom && !inTo { return false }
            }
            return true
        }
        let deduped = filtered.filter { train in
            let key = train.tripId ?? "\(train.label)|\(train.fromStop ?? "")|\(train.toStop ?? "")"
            return seen.insert(key).inserted
        }
        if reportsOnly {
            // Most-reported (most corroborations) at the very top.
            return deduped.sorted { a, b in
                let ca = a.tripId.flatMap { counts[$0] } ?? 0
                let cb = b.tripId.flatMap { counts[$0] } ?? 0
                if ca != cb { return ca > cb }
                return a.label < b.label
            }
        }
        return deduped.sorted { a, b in
            let af = a.tripId.map(flagged.contains) ?? false
            let bf = b.tripId.map(flagged.contains) ?? false
            if af != bf { return af }               // flagged first
            if a.label != b.label { return a.label < b.label }
            return (a.toStop ?? "") < (b.toStop ?? "")
        }
    }

    // MARK: - Subviews

    private var cityPicker: some View {
        Menu {
            ForEach(germanCities) { city in
                Button(city.name) {
                    citySelection.pick(city)
                }
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(loc.t("feed_city"))
                        .font(.caption).foregroundStyle(.secondary)
                    Text(selectedCity.name).font(.body)
                }
                Spacer()
                Image(systemName: "chevron.up.chevron.down").foregroundStyle(.secondary)
            }
            .padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(.separator)))
            .padding(12)
        }
        .tint(.primary)
    }

    // Distinct active line labels for the chosen mode (e.g. "S1", "U6"),
    // shortest-then-alphabetical so "S1" sorts before "S20".
    private var activeLines: [String] {
        let labels = live.trains
            .filter { Self.feedModes.contains($0.mode) && allowsMode($0.mode) }
            .map(\.label)
        return Array(Set(labels)).sorted { ($0.count, $0) < ($1.count, $1) }
    }

    private func modeLabel(_ m: FeedMode) -> String {
        switch m {
        case .sbahn: return loc.t("label_sbahn")
        case .ubahn: return loc.t("label_ubahn")
        case .bus: return loc.t("label_bus")
        case .tram: return loc.t("label_tram")
        case .reports: return loc.t("feed_filter_reports")
        }
    }

    // One line: [filter icon]  [search]  [line dropdown].
    private var filterControls: some View {
        HStack(spacing: 8) {
            // Several modes can be ticked at once (the menu stays open); "All" clears
            // them. Unticking the last one is "All" again.
            Menu {
                Toggle(isOn: Binding(get: { modeFilters.isEmpty },
                                     set: { if $0 { setModeFilters([]) } })) {
                    Text(loc.t("feed_filter_all"))
                }
                // The four modes show as symbols instead of text.
                ForEach([FeedMode.sbahn, .ubahn, .bus, .tram], id: \.self) { m in
                    Toggle(isOn: filterBinding(m)) { Image(systemName: symbol(for: m)) }
                        .accessibilityLabel(Text(modeLabel(m)))
                }
                Toggle(isOn: filterBinding(.reports)) { Text(loc.t("feed_filter_reports")) }
            } label: {
                FilterLinesIcon()
                    .frame(width: 24, height: 24)
                    // Tiny symbols of the active filters in the button's bottom left corner.
                    .overlay(alignment: .bottomLeading) {
                        if !modeFilters.isEmpty {
                            ActiveFilterBadges(modes: FeedMode.allCases.filter(modeFilters.contains).map(\.motisMode))
                                .offset(x: -2, y: 5)
                        }
                    }
                    .frame(width: 28, height: 30)
                    .contentShape(Rectangle())
                    .accessibilityLabel(Text("\(activeFilterLabel) · \(loc.t("feed_filter"))"))
            }
            .menuActionDismissBehavior(.disabled)
            .tint(.primary)

            // Search a line or a station, filling the space between the controls.
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).font(.footnote)
                TextField(loc.t("feed_search_hint"), text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.subheadline)
                if !query.isEmpty {
                    Button { query = "" } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            .frame(maxWidth: .infinity)

            // Line dropdown, fits its content.
            Menu {
                Button(loc.t("feed_all_lines")) { setSelectedLine(nil) }
                ForEach(activeLines, id: \.self) { line in
                    Button(line) { setSelectedLine(line) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(selectedLine ?? loc.t("feed_all_lines"))
                        .lineLimit(1)
                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                }
                .font(.subheadline)
            }
            .tint(.primary)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }


    private func filterBinding(_ m: FeedMode) -> Binding<Bool> {
        Binding(get: { modeFilters.contains(m) },
                set: { on in
                    var modes = modeFilters
                    if on { modes.insert(m) } else { modes.remove(m) }
                    setModeFilters(modes)
                })
    }

    private func symbol(for m: FeedMode) -> String {
        switch m {
        case .sbahn: return "s.circle.fill"
        case .ubahn: return "u.square.fill"
        case .bus: return "bus.fill"
        case .tram: return "tram.fill"
        case .reports: return "exclamationmark.circle.fill"
        }
    }

    /// Spoken label of the active filters (for the filter button's description).
    private var activeFilterLabel: String {
        let labels = FeedMode.allCases.filter(modeFilters.contains).map(modeLabel)
        return labels.isEmpty ? loc.t("feed_filter_all") : labels.joined(separator: ", ")
    }

    private var statusLine: some View {
        Text(statusText)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 4)
    }

    private var statusText: String {
        if !suTrains.isEmpty {
            // Our server is unreachable (the app asks the source directly, less
            // often) or serves an older snapshot: say so instead of "live".
            return loc.t(live.dataDelayed ? "feed_trains_delayed" : "feed_trains_live",
                         suTrains.count, selectedCity.name)
        } else if live.loadError {
            return loc.t("feed_unavailable")
        } else if reportsOnly && !live.trains.isEmpty {
            return loc.t("feed_no_reports")
        } else {
            return loc.t("feed_looking", selectedCity.name)
        }
    }

    @ViewBuilder private var trainList: some View {
        // Spinner only during the initial load, not when a filter is simply empty.
        if live.trains.isEmpty && !live.loadError {
            Spacer()
            ProgressView()
            Spacer()
        } else {
          ScrollViewReader { proxy in
            List(suTrains) { train in
                TrainRow(
                    train: train,
                    flagged: train.tripId.map(reports.flaggedTripIds.contains) ?? false,
                    delayConfirmCount: reports.delayConfirmCount(for: train.tripId),
                    onBuyTicket: { if let url = URL(string: selectedCity.ticketURL) { openURL(url) } }
                )
                .contentShape(Rectangle())
                .onTapGesture {
                    seenStore.markSeen(train.tripId)
                    selectedTrain = train
                }
                // A subtle grey tint on trains already opened (text stays crisp — no fade).
                .listRowBackground(
                    (train.tripId.map(seenStore.seen.contains) ?? false)
                        ? Color(.secondarySystemBackground) : nil
                )
                .id(train.id)
            }
            .listStyle(.plain)
            // Changing a filter jumps back to the top (coming back keeps the position).
            .onChange(of: filterSignature) { _, _ in
                if let first = suTrains.first { proxy.scrollTo(first.id, anchor: .top) }
            }
          }
        }
    }

    private var adSlot: some View {
        NativeAdSlot()
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
    }
}

private struct TrainRow: View {
    @EnvironmentObject private var loc: LocalizationManager
    let train: TrainPosition
    let flagged: Bool
    var delayConfirmCount: Int = 0
    let onBuyTicket: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(train.label)
                .font(.headline.weight(.bold))
                .foregroundStyle(train.displayColor.contrastingLabel)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .frame(width: 52, height: 50)
                .background(train.displayColor, in: RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 2) {
                // Current station bold on top, destination (with the arrow) below;
                // both shrink to fit one line when the name is long.
                Text(train.fromStop ?? "…")
                    .font(.body.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text("→  \(train.toStop ?? "…")")
                    .font(.body)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                HStack(spacing: 8) {
                    Text(loc.t(train.modeLabelKey))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    DelayBadge(delayMinutes: train.delayMinutes, isRealtime: train.isRealtime)
                        .environmentObject(loc)
                    if delayConfirmCount > 0 {
                        Text(loc.t("delay_confirmed_count", delayConfirmCount))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()

            if flagged {
                Text("!")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(Color(red: 0.86, green: 0.16, blue: 0.16), in: Circle())
                // Quick "buy a valid ticket" shortcut on reported trains.
                Button(action: onBuyTicket) {
                    Image(systemName: "ticket")
                        .font(.title3)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(loc.t("buy_ticket"))
            }
        }
        .padding(.vertical, 4)
    }
}

/// Collapsible disruptions banner (Phase 6 / Option 2b): a one-line count of active
/// official disruptions; tap to expand. Fed by the free nationwide gtfs.de feed.
private struct DisruptionsBanner: View {
    @EnvironmentObject private var loc: LocalizationManager
    let disruptions: [Disruption]
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { expanded.toggle() } label: {
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill")
                    Text(loc.t("disruptions_banner", disruptions.count)).fontWeight(.semibold)
                    Spacer()
                    Text(loc.t(expanded ? "disruptions_hide" : "disruptions_show")).font(.caption)
                }
            }
            .tint(.primary)
            if expanded {
                // Cap the height and scroll inside, so a long list stays readable
                // within the banner instead of pushing the feed off screen.
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(disruptions) { d in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.title).font(.subheadline.weight(.semibold))
                                if !d.description.isEmpty {
                                    Text(d.description).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 4)
                        }
                    }
                }
                .frame(maxHeight: 260)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.14))
    }
}

/// Small live-status chip (Phase 6 / Option 1): the real-time delay ("On time" /
/// "+5 min" / "3 min early" in green/amber/red), or a neutral grey "Scheduled" when
/// the trip has no live feed — so the user knows when a train is drawn at its
/// timetable position rather than where it actually is.
private struct DelayBadge: View {
    @EnvironmentObject private var loc: LocalizationManager
    let delayMinutes: Int?
    let isRealtime: Bool

    var body: some View {
        let (text, bg, fg) = style()
        Text(text)
            .font(.caption2)
            .foregroundStyle(fg)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(bg, in: RoundedRectangle(cornerRadius: 6))
    }

    private func style() -> (String, Color, Color) {
        let green = Color(red: 0.11, green: 0.37, blue: 0.13)
        let amber = Color(red: 0.70, green: 0.42, blue: 0.0)
        let red = Color(red: 0.78, green: 0.16, blue: 0.16)
        guard isRealtime, let d = delayMinutes else {
            return (loc.t("delay_scheduled"), Color(.secondarySystemBackground), .secondary)
        }
        switch d {
        case ..<(-1): return (loc.t("delay_early", -d), green, .white)
        case -1...0:  return (loc.t("delay_on_time"), green, .white)
        case 1...4:   return (loc.t("delay_late", d), amber, .white)
        default:      return (loc.t("delay_late", d), red, .white)
        }
    }
}

/// Filter icon: three horizontal lines tied on the RIGHT, getting shorter from
/// top to bottom (mirrors Android's `ic_filter_right`). Shared with MapScreen.
struct FilterLinesIcon: View {
    var body: some View {
        Canvas { ctx, size in
            let right = size.width
            let ys = [size.height * 0.25, size.height * 0.5, size.height * 0.75]
            let lefts = [0.0, size.width * 0.32, size.width * 0.55]
            for (y, left) in zip(ys, lefts) {
                var p = Path()
                p.move(to: CGPoint(x: left, y: y))
                p.addLine(to: CGPoint(x: right, y: y))
                ctx.stroke(p, with: .color(.primary),
                           style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
        }
        .frame(width: 18, height: 16)
    }
}

/// The active filters as tiny symbols for the corner of the filter button, so the
/// user sees at a glance what the list is narrowed to: the mode badges, plus a red
/// "!" for "Current reports" (nil). Port of Android's `ActiveFilterBadges`.
private struct ActiveFilterBadges: View {
    let modes: [String?]

    var body: some View {
        HStack(spacing: 1) {
            ForEach(Array(modes.enumerated()), id: \.offset) { _, mode in
                if let mode {
                    ModeSymbol(mode: mode, size: 10)
                } else {
                    Text("!")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 10, height: 10)
                        .background(Color(red: 0.86, green: 0.16, blue: 0.16), in: Circle())
                }
            }
        }
        .allowsHitTesting(false)
    }
}
