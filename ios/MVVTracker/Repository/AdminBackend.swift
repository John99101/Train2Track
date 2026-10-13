import Foundation
import FirebaseFunctions
import MVVTrackerCore

/// Functions region, same as Firestore (see functions/index.js setGlobalOptions).
enum ReportKeyRegion { static let value = ReportKey.functionsRegion }

/// Inspection statistics from the `control_log` (see controlStats in functions/index.js).
struct ControlStats: Equatable {
    let days: Int
    let total: Int
    let trips: Int
    let truncated: Bool
    let byLine: [(String, Int)]
    let byStation: [(String, Int)]
    let byWeekday: [Int]   // Monday first, Europe/Berlin
    let byHour: [Int]      // 0..23, Europe/Berlin

    static func == (a: ControlStats, b: ControlStats) -> Bool {
        a.days == b.days && a.total == b.total && a.trips == b.trips && a.truncated == b.truncated
            && a.byLine.map(\.0) == b.byLine.map(\.0) && a.byLine.map(\.1) == b.byLine.map(\.1)
            && a.byStation.map(\.0) == b.byStation.map(\.0) && a.byStation.map(\.1) == b.byStation.map(\.1)
            && a.byWeekday == b.byWeekday && a.byHour == b.byHour
    }
}

/// The admin console's server calls. Every one of these Cloud Functions checks the
/// caller's `admin` claim itself; hiding the console from riders is only the UX.
/// Port of Android's `AdminBackend`.
@MainActor
final class AdminBackend {
    static let shared = AdminBackend()

    enum Moderation: String { case verify, dismiss, restore }

    private lazy var functions = Functions.functions(region: ReportKeyRegion.value)

    func moderateTrip(_ tripId: String, _ moderation: Moderation) async throws {
        _ = try await functions.httpsCallable("moderateTrip")
            .call(["tripId": tripId, "action": moderation.rawValue])
    }

    func controlStats(days: Int = 30) async throws -> ControlStats {
        let result = try await functions.httpsCallable("controlStats").call(["days": days])
        guard let d = result.data as? [String: Any] else { throw URLError(.cannotParseResponse) }
        func int(_ k: String) -> Int { (d[k] as? NSNumber)?.intValue ?? 0 }
        func ints(_ k: String) -> [Int] { (d[k] as? [Any] ?? []).map { ($0 as? NSNumber)?.intValue ?? 0 } }
        func pairs(_ k: String) -> [(String, Int)] {
            (d[k] as? [Any] ?? []).compactMap { row in
                guard let r = row as? [Any], let name = r.first as? String else { return nil }
                return (name, r.count > 1 ? (r[1] as? NSNumber)?.intValue ?? 0 : 0)
            }
        }
        return ControlStats(
            days: int("days"), total: int("total"), trips: int("trips"),
            truncated: d["truncated"] as? Bool == true,
            byLine: pairs("byLine"), byStation: pairs("byStation"),
            byWeekday: ints("byWeekday"), byHour: ints("byHour")
        )
    }

    /// Rebuilds the line routes of all cities (takes a few minutes on the server).
    func rebuildRoutes() async throws {
        let callable = functions.httpsCallable("rebuildRoutes")
        callable.timeoutInterval = 9 * 60
        _ = try await callable.call()
    }
}
