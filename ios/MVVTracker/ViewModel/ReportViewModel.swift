import Foundation

/// Whether the rider entered (starts/keeps the alert on that train) or exited
/// (ends this rider's report). Port of Android's `MovementType`.
enum Movement: Hashable {
    case entered
    case exited
}

/// Backs the report flow for one specific train (reached by tapping it).
/// Port of `viewmodel/ReportViewModel.kt`.
///  - `.entered` starts/keeps the event on that train (dedup per rider);
///  - `.exited` records a "has left" vote: it flips this rider's own report, and
///    other riders' exits clear the "!" once two of them agree (ReportAggregation).
@MainActor
final class ReportViewModel: ObservableObject {
    enum SubmitState: Equatable {
        case idle
        case submitting
        /// What was sent, so the confirmation fits the action (Android 0.3.1).
        case success(Movement)
        case error(String)
    }

    /// Separate state for the delay confirm/deny votes (Phase 6 / Option 2), so a
    /// delay vote never disturbs the inspector-report submit state.
    enum DelayVoteState: Equatable {
        case idle
        case submitting
        case done(Bool)
        case error(String)
    }

    /// "How full is it?": the level being sent or sent, kept apart from the other votes.
    enum OccupancyVoteState: Equatable {
        case idle
        case submitting(Int)
        case done(Int)
        case error(String)
    }

    @Published private(set) var state: SubmitState = .idle
    @Published private(set) var delayVoteState: DelayVoteState = .idle
    @Published private(set) var occupancyVoteState: OccupancyVoteState = .idle

    private let backend = UserReportBackend.shared

    /// `city` = the report city of the train (a city id or "other"); every app only
    /// loads the reports of the cities it shows.
    func submit(tripId: String?, line: String?, station: String?, movement: Movement, city: String) {
        guard let tripId, !tripId.isEmpty else {
            state = .error(LocalizationManager.shared.t("report_no_train"))
            return
        }
        // One at a time: a double tap would add a second row to the statistics.
        if case .submitting = state { return }
        state = .submitting
        Task {
            do {
                switch movement {
                case .entered:
                    // Android stores the report locally first (Room), then sends it.
                    OwnReportsStore.shared.entered(tripId)
                    do {
                        try await backend.submitReport(
                            tripId: tripId,
                            line: line?.isEmpty == false ? line! : "",
                            station: station ?? "",
                            description: "Entered at \(station ?? "?")",
                            city: city
                        )
                    } catch {
                        // Not sent: the report exists nowhere but on this phone. Take
                        // its "!" back, so the phone does not show a report nobody
                        // else can see.
                        OwnReportsStore.shared.exited(tripId)
                        throw error
                    }
                case .exited:
                    OwnReportsStore.shared.exited(tripId)
                    try await backend.endReport(tripId: tripId, city: city)
                }
                state = .success(movement)
            } catch {
                state = .error(LocalizationManager.shared.t("report_error_send"))
            }
        }
    }

    /// Records how full the train is, 1 (many seats free) to 5 (very full).
    func voteOccupancy(tripId: String?, level: Int, city: String) {
        guard let tripId, !tripId.isEmpty else { return }
        occupancyVoteState = .submitting(level)
        Task {
            do {
                try await backend.submitOccupancyVote(tripId: tripId, level: level, city: city)
                occupancyVoteState = .done(level)
            } catch {
                occupancyVoteState = .error(LocalizationManager.shared.t("report_error_send"))
            }
        }
    }

    /// Records a delay vote for the train (Phase 6 / Option 2): `confirm` = "yes,
    /// it's late", else "no, on time". Independent of the inspector report.
    func voteDelay(tripId: String?, confirm: Bool, city: String) {
        guard let tripId, !tripId.isEmpty else { return }
        delayVoteState = .submitting
        Task {
            do {
                try await backend.submitDelayVote(tripId: tripId, confirm: confirm, city: city)
                delayVoteState = .done(confirm)
            } catch {
                delayVoteState = .error(LocalizationManager.shared.t("report_error_send"))
            }
        }
    }
}
