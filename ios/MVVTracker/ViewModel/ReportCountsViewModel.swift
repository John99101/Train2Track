import Foundation
import Combine
import FirebaseFirestore
import MVVTrackerCore

/// Live report state per train across all devices (Firestore), used to flag trains
/// with a red "!" in the Feed and on the Map. Mirrors Android's
/// `EventsViewModel.remoteReportCounts` / `observeReportedTrips`: the "!" follows
/// `ReportAggregation` exactly (spam limit, moderation, two "has left" to clear).
@MainActor
final class ReportCountsViewModel: ObservableObject {
    /// Every train with live reports (also dismissed / spam blocked ones).
    @Published private(set) var trips: [String: TripReports] = [:]
    /// Crowd delay tally per trip (Phase 6 / Option 2): tripId -> (confirm, onTime).
    @Published private(set) var delayVotes: [String: (confirm: Int, onTime: Int)] = [:]

    private var observation: ReportObservation?
    private var delayListener: ListenerRegistration?
    /// The report cities being loaded (see `reportScope` in MVVTrackerCore).
    private var cities: [String] = []
    private let own = OwnReportsStore.shared
    private var ownSub: AnyCancellable?

    init() {
        // Re-render when this device's own reports change.
        ownSub = own.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    /// What the "!" shows per train (0 = not flagged).
    var counts: [String: Int] { trips.mapValues(\.flagged) }

    /// Trains flagged with a "!": those reported by enough riders across all
    /// devices (server rules), plus this device's own recent report for instant
    /// feedback. Exactly Android's `flaggedTripIds = local + remote`.
    var flaggedTripIds: Set<String> {
        let remote = Set(trips.filter { $0.value.flagged >= ReportKey.minCorroborations }.keys)
        return remote.union(own.activeTripIds())
    }

    /// Confirmations to show for a trip — only when more riders confirm a delay than
    /// call it on time (so a stray vote doesn't light up the row); 0 otherwise.
    func delayConfirmCount(for tripId: String?) -> Int {
        guard let tripId, let v = delayVotes[tripId] else { return 0 }
        return v.confirm > v.onTime ? v.confirm : 0
    }

    /// Loads the reports and delay votes of `cities` only: the cities the screen
    /// shows, not every report in Germany. Calling it again with other cities
    /// restarts the listeners; what is shown stays until the new data arrives.
    func start(cities: [String]) {
        if cities == self.cities, observation != nil { return }
        stop()
        self.cities = cities
        guard !cities.isEmpty else { return }
        observation = UserReportBackend.shared.observeReportedTrips(cities: cities) { [weak self] trips in
            Task { @MainActor in self?.trips = trips }
        }
        delayListener = UserReportBackend.shared.observeDelayVotes(cities: cities) { [weak self] votes in
            Task { @MainActor in self?.delayVotes = votes }
        }
    }

    func stop() {
        observation?.cancel(); observation = nil
        delayListener?.remove(); delayListener = nil
    }

    deinit {
        observation?.cancel()
        delayListener?.remove()
    }
}
