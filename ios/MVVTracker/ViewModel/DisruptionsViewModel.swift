import Foundation
import FirebaseFirestore

/// One live disruption/alert (Phase 6 / Option 2b). Produced server-side by the
/// `refreshDisruptions` Cloud Function from the gtfs.de feed and stored in the
/// `disruptions` collection — each device reads a few KB, never the 35 MB feed.
struct Disruption: Identifiable, Equatable {
    let id: String
    let title: String
    let description: String
    let affected: [String]
    let startEpochMillis: Int64
    let endEpochMillis: Int64?
    let severe: Bool
}

/// Streams the `disruptions` collection. Reads are tiny (a few KB), so this is a
/// plain live listener — no throttling, no 35 MB download on the device.
@MainActor
final class DisruptionsViewModel: ObservableObject {
    @Published private(set) var disruptions: [Disruption] = []

    private var listener: ListenerRegistration?

    func start() {
        guard listener == nil else { return }
        listener = UserReportBackend.shared.observeDisruptions { [weak self] list in
            Task { @MainActor in self?.disruptions = list }
        }
    }

    func stop() {
        listener?.remove()
        listener = nil
    }

    deinit { listener?.remove() }
}
