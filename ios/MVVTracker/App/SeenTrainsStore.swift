import Foundation
import Combine

/// Remembers which trains the user has already opened, so the Feed can dim them
/// (like a read email) and optionally hide them. Stored locally in UserDefaults
/// only (no backend, no personal data). Trip ids change per journey, so this
/// naturally becomes stale day to day — the intended behaviour. Port of
/// `SeenTrainsStore.kt`.
@MainActor
final class SeenTrainsStore: ObservableObject {
    static let shared = SeenTrainsStore()

    @Published private(set) var seen: Set<String>

    private let key = "seen_trip_ids"

    private init() {
        seen = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
    }

    func markSeen(_ tripId: String?) {
        guard let tripId, !tripId.isEmpty, !seen.contains(tripId) else { return }
        seen.insert(tripId)
        UserDefaults.standard.set(Array(seen), forKey: key)
    }

    /// "Delete my data": forget every opened train.
    func clear() {
        seen = []
        UserDefaults.standard.removeObject(forKey: key)
    }
}
