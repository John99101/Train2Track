import Foundation
import Combine
import MVVTrackerCore

/// This device's own "on board" reports, kept locally like Android's Room event
/// table (`EventRepository.submitUserReport`). The Feed and the Map flag a train
/// when the server rules say so (`ReportAggregation`) OR when this device itself
/// reported it within the report lifetime, exactly like Android's
/// `flaggedTripIds = local + remote`. So a rider always sees their own "!" until
/// they report "has left" or it expires, even before the write round-trips.
@MainActor
final class OwnReportsStore: ObservableObject {
    static let shared = OwnReportsStore()

    /// tripId -> when this device reported "on board" (epoch ms).
    @Published private(set) var startedAt: [String: Int64]

    private let key = "own_active_reports"

    private init() {
        let saved = UserDefaults.standard.dictionary(forKey: key) as? [String: NSNumber] ?? [:]
        startedAt = saved.mapValues(\.int64Value)
        // Remove what expired while the app was closed, so nothing older than the
        // report lifetime stays on the device.
        save()
    }

    /// "On board": one active report per device per train (an earlier one is replaced).
    func entered(_ tripId: String) {
        startedAt[tripId] = Self.nowMs()
        save()
    }

    /// "Has left": ends this device's report for the train.
    func exited(_ tripId: String) {
        guard startedAt.removeValue(forKey: tripId) != nil else { return }
        save()
    }

    /// "Delete my data".
    func clear() {
        startedAt = [:]
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// Trains this device reported within the report lifetime.
    func activeTripIds(nowMs: Int64 = OwnReportsStore.nowMs()) -> Set<String> {
        let cutoff = nowMs - ReportKey.reportTtlMillis
        return Set(startedAt.filter { $0.value >= cutoff }.keys)
    }

    private func save() {
        // Drop expired entries so the store never grows.
        let cutoff = Self.nowMs() - ReportKey.reportTtlMillis
        startedAt = startedAt.filter { $0.value >= cutoff }
        UserDefaults.standard.set(startedAt.mapValues { NSNumber(value: $0) }, forKey: key)
    }

    nonisolated static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
