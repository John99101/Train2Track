import Foundation
import MVVTrackerCore

/// Full line routes per city (every S-Bahn, U-Bahn, tram, bus and regional line),
/// built once a day on the server and kept on the device (Application Support), so
/// the map draws them right away instead of fetching each line on every visit.
/// Keyed by `routeKey(mode:label:)`. Port of Android's `RouteStore`.
actor RouteStore {
    static let shared = RouteStore()

    private struct Saved: Codable {
        let fetchedAt: Int64
        let rows: [[String]]
    }

    private let dir = LiveCacheDir.url("live_routes")
    private let api = LiveApiClient()
    private static let maxAgeMs: Int64 = 24 * 60 * 60 * 1000

    /// The city's saved routes, decoded; empty when none yet.
    func cached(_ cityId: String) -> [String: [GeoPoint]] {
        read(cityId).map { decode($0.rows) } ?? [:]
    }

    /// Downloads the city's routes when the saved copy is missing or older than a
    /// day; returns the new routes, or nil when nothing changed or the server is
    /// unreachable (the saved copy and on-demand fetching keep working).
    func refreshIfStale(_ cityId: String) async -> [String: [GeoPoint]]? {
        let saved = read(cityId)
        if let saved, nowMs() - saved.fetchedAt < Self.maxAgeMs { return nil }
        guard let rows = try? await api.routes(cityId) else { return nil }
        let fresh = rows.filter { $0.count >= 4 && !$0[3].isEmpty }
        if fresh.isEmpty { return nil }
        // Keep lines only fetched on demand that the server set does not have.
        let serverKeys = Set(fresh.map { routeKey(mode: $0[1], label: $0[0]) })
        let extras = (read(cityId)?.rows ?? []).filter { $0.count >= 4 && !serverKeys.contains(routeKey(mode: $0[1], label: $0[0])) }
        let all = fresh + extras
        write(cityId, Saved(fetchedAt: nowMs(), rows: all))
        return decode(all)
    }

    /// Saves a route fetched on demand (a line the server set did not have yet).
    func addOnDemand(_ cityId: String, mode: String, label: String, points: [GeoPoint]) {
        let saved = read(cityId)
        let rows = (saved?.rows ?? []).filter { !($0.count >= 2 && $0[0] == label && $0[1] == mode) }
            + [[label, mode, "", GeoPolyline.encode(points)]]
        // fetchedAt 0 when there is no server copy yet, so it is still fetched.
        write(cityId, Saved(fetchedAt: saved?.fetchedAt ?? 0, rows: rows))
    }

    private func decode(_ rows: [[String]]) -> [String: [GeoPoint]] {
        var out: [String: [GeoPoint]] = [:]
        for r in rows where r.count >= 4 && !r[3].isEmpty {
            let pts = GeoPolyline.decode(r[3])
            if pts.count >= 2 { out[routeKey(mode: r[1], label: r[0])] = pts }
        }
        return out
    }

    private func read(_ cityId: String) -> Saved? {
        let file = fileFor(cityId)
        guard let data = try? Data(contentsOf: file) else { return nil }
        guard let saved = try? JSONDecoder().decode(Saved.self, from: data) else {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return saved
    }

    private func write(_ cityId: String, _ saved: Saved) {
        guard let data = try? JSONEncoder().encode(saved) else { return }
        try? data.write(to: fileFor(cityId), options: .atomic)
    }

    private func fileFor(_ cityId: String) -> URL { dir.appendingPathComponent("\(cityId).json") }
    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
