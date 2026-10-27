import Foundation
import Combine
import MVVTrackerCore

/// The stations the rider opened last, newest first, at most eight. Stored on the
/// phone only (UserDefaults); "Delete my data" clears them. Port of Android's
/// `StationRecents`.
@MainActor
final class StationRecents: ObservableObject {
    static let shared = StationRecents()

    @Published private(set) var recent: [StationRef]

    private let d = UserDefaults.standard
    private static let key = "stations_recent"
    private static let max = 8

    private init() {
        recent = (d.data(forKey: Self.key)).flatMap { try? JSONDecoder().decode([StationRef].self, from: $0) } ?? []
    }

    func opened(_ station: StationRef) {
        recent = Array(([station] + recent.filter { !($0.id == station.id && $0.cityId == station.cityId) }).prefix(Self.max))
        d.set(try? JSONEncoder().encode(recent), forKey: Self.key)
    }

    /// "Delete my data".
    func clear() {
        recent = []
        d.removeObject(forKey: Self.key)
    }
}
