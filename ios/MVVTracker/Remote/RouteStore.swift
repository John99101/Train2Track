import Foundation
import MVVTrackerCore

/// A line's route: its pieces. The first is the whole route of one journey, the
/// others only the parts other journeys add (a branch, the other end of a short
/// run). Older sets have one piece per line. Port of Android's `LineRoute`.
typealias LineRoute = [[GeoPoint]]

/// Full line routes per city (every S-Bahn, U-Bahn, tram, bus and regional line,
/// with their branches). The app ships with the routes of every city (the folder
/// "mapdata", written by tools/bundle_map_data.js before a release), so the map
/// draws them at once and without a download. Once a day it asks the server
/// whether the set changed: "not modified" costs no data, a new set is saved
/// (Application Support) and used from then on. All routes come from the
/// Train2Track server: the app never asks the timetable service for one itself.
/// Keyed by `routeKey(mode:label:)`. Port of Android's `RouteStore`.
actor RouteStore {
    static let shared = RouteStore()

    private struct Saved: Codable {
        let fetchedAt: Int64
        let rows: [[String]]
        var etag: String? = nil
    }

    private let dir = LiveCacheDir.url("live_routes")
    private let api = LiveApiClient()
    private static let maxAgeMs: Int64 = 24 * 60 * 60 * 1000
    private var bundledByCity: [String: Saved?] = [:]

    /// The city's routes on the phone, decoded; empty when none.
    func cached(_ cityId: String) -> [String: LineRoute] {
        newestCopy(cityId).map { decode($0.rows) } ?? [:]
    }

    /// Asks the server whether the city's routes changed when the copy on the phone
    /// is older than a day, has no ETag, or there is none; returns the new routes, or nil when
    /// nothing changed or the server is unreachable (the copy and `fetchLine` keep
    /// working). With a copy the server only sends the set when it changed (ETag,
    /// 304 without a body otherwise).
    func refreshIfStale(_ cityId: String) async -> [String: LineRoute]? {
        let copy = newestCopy(cityId)
        // A copy without an ETag was saved by an app version before the line branches
        // (one piece per line): ask at once, so the whole lines arrive.
        if let copy, copy.etag != nil, nowMs() - copy.fetchedAt < Self.maxAgeMs { return nil }
        guard let answer = try? await api.routes(cityId, etag: copy?.etag) else { return nil }
        // Read again: a line fetched on demand meanwhile (fetchLine) must not be lost.
        let current = newestCopy(cityId)
        switch answer {
        case .notModified:
            if let current { write(cityId, Saved(fetchedAt: nowMs(), rows: current.rows, etag: current.etag)) }
            return nil
        case .data(let rows, let etag):
            let fresh = rows.filter { $0.count >= 4 && !$0[3].isEmpty }
            if fresh.isEmpty { return nil }
            let all = withExtras(fresh, current?.rows ?? [])
            write(cityId, Saved(fetchedAt: nowMs(), rows: all, etag: etag))
            return decode(all)
        }
    }

    /// The route of one line the set does not have (a long distance train, a night
    /// line), from the server, and saves it for the city. `tripId` is a vehicle
    /// running on that line right now: the server builds the route from it once for
    /// everybody. nil when the server has none or cannot be reached.
    /// Port of Android's `RouteStore.fetchLine`.
    func fetchLine(_ cityId: String, mode: String, label: String, tripId: String) async -> LineRoute? {
        guard let row = try? await api.route(cityId, mode: mode, label: label, tripId: tripId) else { return nil }
        let pieces = Self.pieces(row)
        guard !pieces.isEmpty else { return nil }
        let copy = newestCopy(cityId)
        let rows = (copy?.rows ?? []).filter { !($0.count >= 2 && $0[0] == label && $0[1] == mode) } + [row]
        // fetchedAt 0 when there is no server copy yet, so it is still fetched.
        write(cityId, Saved(fetchedAt: copy?.fetchedAt ?? 0, rows: rows, etag: copy?.etag))
        return pieces
    }

    /// The newest copy on the phone: the saved one, or the one shipped with the app
    /// when that is newer (an app update brings newer routes). Lines the saved copy
    /// got on demand and the newer one lacks are kept.
    private func newestCopy(_ cityId: String) -> Saved? {
        let saved = read(cityId)
        guard let shipped = bundled(cityId) else { return saved }
        guard let saved, saved.fetchedAt < shipped.fetchedAt else { return saved ?? shipped }
        return Saved(fetchedAt: shipped.fetchedAt, rows: withExtras(shipped.rows, saved.rows), etag: shipped.etag)
    }

    /// `fresh` plus the lines of `old` it does not have (lines fetched on demand).
    private func withExtras(_ fresh: [[String]], _ old: [[String]]) -> [[String]] {
        let keys = Set(fresh.map { routeKey(mode: $0[1], label: $0[0]) })
        return fresh + old.filter { $0.count >= 4 && !keys.contains(routeKey(mode: $0[1], label: $0[0])) }
    }

    /// The routes shipped with the app for `cityId` (read once), or nil when it has none.
    private func bundled(_ cityId: String) -> Saved? {
        if let known = bundledByCity[cityId] { return known }
        var result: Saved?
        if let url = Bundle.main.url(forResource: "\(cityId).routes", withExtension: "json", subdirectory: "mapdata"),
           let data = try? Data(contentsOf: url),
           let rows = try? LiveSnapshot.parseRoutes(data),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            let usable = rows.filter { $0.count >= 4 && !$0[3].isEmpty }
            if !usable.isEmpty {
                result = Saved(fetchedAt: (json["fetchedAt"] as? NSNumber)?.int64Value ?? 0,
                               rows: usable, etag: json["etag"] as? String)
            }
        }
        bundledByCity[cityId] = result
        return result
    }

    private func decode(_ rows: [[String]]) -> [String: LineRoute] {
        var out: [String: LineRoute] = [:]
        for r in rows where r.count >= 4 && !r[3].isEmpty {
            let pieces = Self.pieces(r)
            if !pieces.isEmpty { out[routeKey(mode: r[1], label: r[0])] = pieces }
        }
        return out
    }

    /// The pieces of a row `[label, mode, colour, piece, piece...]`.
    private static func pieces(_ row: [String]) -> LineRoute {
        row.dropFirst(3).map { GeoPolyline.decode($0) }.filter { $0.count >= 2 }
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
