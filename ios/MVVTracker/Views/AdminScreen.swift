import SwiftUI
import MVVTrackerCore

/// Admin only console: who is online, which trains are reported right now (verify,
/// dismiss false reports, restore), and the inspection statistics. A rider
/// never sees this tab (see `MainTabs`), and every action is re-checked on the
/// server against the caller's admin claim. Port of `AdminScreen.kt`.
struct AdminScreen: View {
    @EnvironmentObject private var loc: LocalizationManager
    @ObservedObject var admin: AdminViewModel

    var body: some View {
        NavigationStack {
            Group {
                if !admin.isAdmin {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(loc.t("admin_restricted_title")).font(.title2.bold())
                        Text(loc.t("admin_restricted_body"))
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                } else {
                    console
                }
            }
            .navigationTitle(loc.t("admin_title"))
        }
    }

    private var console: some View {
        List {
            Section {
                Text(loc.t("admin_sub")).font(.subheadline).foregroundStyle(.secondary)
                liveUsers
                if let msg = admin.actionError {
                    Text(loc.t("admin_action_failed", msg)).font(.footnote).foregroundStyle(.red)
                }
            }

            Section(loc.t("admin_reported_trains")) {
                if admin.reportedTrips.isEmpty {
                    Text(loc.t("admin_no_reports")).foregroundStyle(.secondary)
                }
                ForEach(admin.reportedTrips, id: \.tripId) { trip in
                    ReportedTripCard(trip: trip, admin: admin)
                }
            }

            Section(loc.t("admin_stats_title")) {
                statsSection
            }

            Section {
                Button {
                    admin.rebuildRoutes()
                } label: {
                    Text(loc.t(admin.routesRebuilding ? "admin_routes_rebuilt" : "admin_routes_rebuild"))
                        .frame(maxWidth: .infinity)
                }
                .disabled(admin.routesRebuilding)
            }
        }
    }

    @ViewBuilder private var liveUsers: some View {
        if let live = admin.liveStats {
            VStack(alignment: .leading, spacing: 2) {
                Text(loc.plural("admin_active_users", live.activeUsers)).font(.headline)
                Text(loc.t("admin_active_users_split", live.android, live.ios))
                    .font(.caption).foregroundStyle(.secondary)
            }
        } else {
            Text(loc.t("admin_active_users_unknown"))
        }
    }

    @ViewBuilder private var statsSection: some View {
        switch admin.stats {
        case .idle, .failed:
            if admin.stats == .failed {
                Text(loc.t("admin_stats_failed")).foregroundStyle(.red)
            }
            Button(loc.t("admin_stats_load")) { admin.loadStats() }
        case .loading:
            HStack { ProgressView(); Text(loc.t("admin_stats_loading")) }
        case .loaded(let s):
            StatsContent(stats: s)
        }
    }
}

private struct ReportedTripCard: View {
    @EnvironmentObject private var loc: LocalizationManager
    let trip: TripReports
    @ObservedObject var admin: AdminViewModel

    private var title: String {
        let t = [trip.line, trip.station].compactMap { $0 }.joined(separator: " · ")
        return t.isEmpty ? trip.tripId : t
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(loc.t("admin_report_counts", trip.onBoard, trip.exits)).font(.callout)
            let states = stateLabels
            if !states.isEmpty {
                Text(states.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button(loc.t("admin_verify")) { admin.moderate(trip.tripId, .verify) }
                    .buttonStyle(.borderedProminent)
                    .tint(.primary)
                    .foregroundStyle(Color(.systemBackground))
                Button(loc.t("admin_dismiss")) { admin.moderate(trip.tripId, .dismiss) }
                    .buttonStyle(.bordered)
                Button(loc.t("admin_restore")) { admin.moderate(trip.tripId, .restore) }
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 4)
        // A dismissed train without fresh entries is greyed out.
        .listRowBackground(trip.dismissed > 0 && trip.onBoard == 0 ? Color(.secondarySystemBackground) : nil)
    }

    private var stateLabels: [String] {
        var out: [String] = []
        if trip.verified { out.append(loc.t("admin_state_verified")) }
        if trip.dismissed > 0 { out.append(loc.t("admin_state_dismissed")) }
        if trip.throttled > 0 { out.append(loc.t("admin_state_throttled", trip.throttled)) }
        return out
    }
}

private struct StatsContent: View {
    @EnvironmentObject private var loc: LocalizationManager
    let stats: ControlStats

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(loc.t("admin_stats_total", stats.total, stats.trips)).font(.callout)

            heading("admin_stats_by_line")
            let lineMax = stats.byLine.map(\.1).max() ?? 0
            ForEach(Array(stats.byLine.prefix(10).enumerated()), id: \.offset) { _, row in
                BarRow(label: row.0, value: row.1, max: lineMax)
            }

            heading("admin_stats_by_weekday")
            let dayMax = stats.byWeekday.max() ?? 0
            ForEach(Array(stats.byWeekday.enumerated()), id: \.offset) { i, n in
                BarRow(label: weekday(i), value: n, max: dayMax)
            }

            heading("admin_stats_by_hour")
            HourChart(byHour: stats.byHour)

            heading("admin_stats_by_station")
            let stationMax = stats.byStation.map(\.1).max() ?? 0
            ForEach(Array(stats.byStation.prefix(10).enumerated()), id: \.offset) { _, row in
                BarRow(label: row.0, value: row.1, max: stationMax)
            }
        }
    }

    private func heading(_ key: String) -> some View {
        Text(loc.t(key)).font(.subheadline.weight(.semibold)).padding(.top, 10)
    }

    /// Monday first, in the app language.
    private func weekday(_ i: Int) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.locale = Locale(identifier: loc.effectiveCode)
        let names = cal.shortWeekdaySymbols          // Sunday first
        return names[(i + 1) % 7]
    }
}

/// A label, a proportional bar and the count.
private struct BarRow: View {
    let label: String
    let value: Int
    let max: Int

    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.caption).lineLimit(1).frame(width: 110, alignment: .leading)
            GeometryReader { geo in
                let fraction = max > 0 ? CGFloat(value) / CGFloat(max) : 0
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.accentColor)
                    .frame(width: geo.size.width * fraction, height: 10)
                    .frame(maxHeight: .infinity, alignment: .center)
            }
            .frame(height: 12)
            Text("\(value)").font(.caption).frame(width: 44, alignment: .leading)
        }
    }
}

/// 24 small columns, one per hour of the day (Europe/Berlin).
private struct HourChart: View {
    let byHour: [Int]

    var body: some View {
        let max = Swift.max(byHour.max() ?? 0, 1)
        VStack(spacing: 2) {
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(Array(byHour.enumerated()), id: \.offset) { _, n in
                    GeometryReader { geo in
                        VStack {
                            Spacer(minLength: 0)
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color.accentColor)
                                .frame(height: geo.size.height * Swift.max(CGFloat(n) / CGFloat(max), 0.02))
                        }
                    }
                }
            }
            .frame(height: 64)
            HStack {
                ForEach(["0", "6", "12", "18", "23"], id: \.self) { h in
                    Text(h).font(.caption2).foregroundStyle(.secondary)
                    if h != "23" { Spacer() }
                }
            }
        }
    }
}
