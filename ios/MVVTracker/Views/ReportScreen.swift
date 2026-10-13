import SwiftUI
import MVVTrackerCore

/// Report screen for one specific train (opened by tapping it on the map/feed).
/// Shows the line + current station (read-only) and a Movement choice —
/// Entered (starts/keeps the alert) or Exited (ends this rider's report).
/// Port of `ReportEventScreen.kt`.
struct ReportScreen: View {
    let train: TrainPosition
    /// The selected city's operator ticket page (defaults to Deutsche Bahn).
    var ticketURL: String = dbTicketsURL
    /// The city this train's reports are filed under (a city id or "other").
    var reportCity: String = cityOther

    @EnvironmentObject private var loc: LocalizationManager
    @StateObject private var vm = ReportViewModel()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    // Movement is selected first (like Android's Entered/Exited chips), then the
    // rider taps a single Submit button.
    @State private var movement: Movement = .entered

    /// Current station (Android passes `fromStop ?: toStop`), empty when unknown.
    private var station: String { train.fromStop ?? train.toStop ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                // Train identity (read-only): line + current station.
                Section {
                    HStack(spacing: 8) {
                        ModeSymbol(mode: train.mode, size: 26)
                        Text(train.label)
                            .font(.title3.weight(.bold))
                            .foregroundStyle(train.displayColor.contrastingLabel)
                            .padding(.horizontal, 10).padding(.vertical, 3)
                            .background(train.displayColor, in: Capsule())
                        Spacer()
                        Text(station).foregroundStyle(.secondary)
                    }
                    if let to = train.toStop {
                        LabeledContent(loc.t("report_direction")) { Text(to) }
                    }
                } header: {
                    Text(loc.t("report_subtitle"))
                        .textCase(nil)
                }

                // Ticket inspection report: on board / has left + submit, with the
                // neutral legal framing as the section footer.
                Section {
                    Picker(loc.t("report_movement"), selection: $movement) {
                        Text(loc.t("movement_entered")).tag(Movement.entered)
                        Text(loc.t("movement_exited")).tag(Movement.exited)
                    }
                    .pickerStyle(.segmented)

                    Button {
                        vm.submit(tripId: train.tripId, line: train.label,
                                  station: train.fromStop ?? train.toStop, movement: movement,
                                  city: reportCity)
                    } label: {
                        Text(loc.t("report_submit")).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.primary)
                    .foregroundStyle(Color(.systemBackground))
                    .disabled(vm.state == .submitting)

                    reportStatus
                } header: {
                    Text(loc.t("report_inspection_title"))
                } footer: {
                    Text(loc.t("report_control_hint"))
                }

                // Crowd delay vote (separate backend collection; never the "!").
                Section {
                    HStack(spacing: 10) {
                        Button {
                            vm.voteDelay(tripId: train.tripId, confirm: true, city: reportCity)
                        } label: {
                            Text(loc.t("delay_vote_yes")).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color(red: 0.70, green: 0.42, blue: 0.0))
                        .foregroundStyle(.white)

                        Button {
                            vm.voteDelay(tripId: train.tripId, confirm: false, city: reportCity)
                        } label: {
                            Text(loc.t("delay_vote_no")).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(.primary)
                    }
                    .disabled(vm.delayVoteState == .submitting)
                    if case .done(let late) = vm.delayVoteState {
                        StatusNote(text: loc.t(late ? "delay_vote_thanks_late" : "delay_vote_thanks_on_time"),
                                   systemImage: "checkmark.circle.fill", color: .green)
                    } else if case .error(let msg) = vm.delayVoteState {
                        StatusNote(text: msg, systemImage: "exclamationmark.triangle.fill", color: .red)
                    }
                } header: {
                    Text(loc.t("delay_vote_title"))
                } footer: {
                    Text(loc.t("delay_vote_sub"))
                }

                // Carry a valid ticket: one tap to the operator ticket page.
                Section {
                    Button {
                        if let url = URL(string: ticketURL) { openURL(url) }
                    } label: {
                        Label(loc.t("buy_ticket"), systemImage: "ticket")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                } footer: {
                    Text(loc.t("report_legal_note"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                // Reports of other riders are shown on this journey: the way to
                // report unlawful content belongs where the content is (Art. 16
                // Digital Services Act).
                Section {
                    Link(loc.t("settings_report_content"), destination: AppInfo.reportContentURL)
                        .font(.footnote)
                        .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle(loc.t("report_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(loc.t("report_close")) { dismiss() }
                }
            }
        }
    }

    /// Shown right below "Submit report" (like Android); the screen stays open so
    /// the rider can read it and closes with "Close".
    @ViewBuilder private var reportStatus: some View {
        switch vm.state {
        case .submitting:
            HStack(spacing: 8) { ProgressView().controlSize(.small); Text(loc.t("report_submitting")) }
                .font(.caption).foregroundStyle(.secondary)
        case .success(let movement):
            StatusNote(text: loc.t(movement == .entered ? "report_success_entered" : "report_success_exited"),
                       systemImage: "checkmark.circle.fill", color: .green)
        case .error(let msg):
            StatusNote(text: msg, systemImage: "exclamationmark.triangle.fill", color: .red)
        case .idle:
            EmptyView()
        }
    }
}

/// A small confirmation or error line under a button (caption size, tight icon).
private struct StatusNote: View {
    let text: String
    let systemImage: String
    let color: Color

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: systemImage)
            Text(text)
        }
        .font(.caption)
        .foregroundStyle(color)
    }
}
