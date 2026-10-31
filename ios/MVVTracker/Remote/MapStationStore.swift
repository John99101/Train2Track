import Foundation
import MVVTrackerCore

/// The station dots of each city. The app ships with them (the folder "mapdata",
/// written by tools/bundle_map_data.js before a release); once a week, and only
/// while the "Stations" button is on, it asks the server whether they changed
/// ("not modified" costs no data). Without a shipped copy (a city added later) they
/// are downloaded when the button is switched on, then kept: a week, or a day while
/// the server says its list is not complete yet. Public timetable data, no personal
/// data. Port of Android's `MapStationStore`.
actor MapStationStore {
    static let shared = MapStationStore()

    private struct Saved: Codable {
        let fetchedAt: Int64
        /// The server's answer as it came (see `StationParser.mapStations`).
        let data: Data
        var etag: String? = nil
    }

    private let dir = LiveCacheDir.url("map_stations")
    private let api = StationApiClient()
    private var memory: [String: Saved] = [:]
    /// When a download for a city last failed: not tried again for ten minutes.
    private var failedAt: [String: Int64] = [:]
    private static let maxAgeMs: Int64 = 7 * 24 * 60 * 60 * 1000
    private static let incompleteMaxAgeMs: Int64 = 24 * 60 * 60 * 1000
    private static let retryMs: Int64 = 10 * 60 * 1000

    /// The city's stations: the copy on the phone while fresh, else asked from the
    /// server; the old ones when that fails.
    func stations(_ cityId: String) async -> [MapStation] {
        let now = nowMs()
        let saved = memory[cityId] ?? newest(read(cityId), bundled(cityId))
        if let saved { memory[cityId] = saved }
        let parsed = saved.flatMap { try? StationParser.mapStations($0.data) }
        let maxAge = parsed?.complete == false ? Self.incompleteMaxAgeMs : Self.maxAgeMs
        if let saved, now - saved.fetchedAt < maxAge { return parsed?.stations ?? [] }
        if now - (failedAt[cityId] ?? 0) < Self.retryMs { return parsed?.stations ?? [] }
        let answer = try? await api.mapStations(cityId: cityId, etag: saved?.etag)
        if case .notModified? = answer, let saved {
            let kept = Saved(fetchedAt: now, data: saved.data, etag: saved.etag)
            memory[cityId] = kept
            write(cityId, kept)
            return parsed?.stations ?? []
        }
        if case .data(let data, let etag)? = answer,
           let fresh = try? StationParser.mapStations(data), !fresh.stations.isEmpty {
            let entry = Saved(fetchedAt: now, data: data, etag: etag)
            memory[cityId] = entry
            write(cityId, entry)
            return fresh.stations
        }
        failedAt[cityId] = now
        return parsed?.stations ?? []
    }

    private func newest(_ saved: Saved?, _ bundled: Saved?) -> Saved? {
        guard let saved else { return bundled }
        guard let bundled, bundled.fetchedAt > saved.fetchedAt else { return saved }
        return bundled
    }

    /// The stations shipped with the app for `cityId`, or nil when it has none.
    private func bundled(_ cityId: String) -> Saved? {
        guard let url = Bundle.main.url(forResource: "\(cityId).stops", withExtension: "json", subdirectory: "mapdata"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return Saved(fetchedAt: (json["fetchedAt"] as? NSNumber)?.int64Value ?? 0, data: data,
                     etag: json["etag"] as? String)
    }

    private func read(_ cityId: String) -> Saved? {
        let file = dir.appendingPathComponent("\(cityId).json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        guard let saved = try? JSONDecoder().decode(Saved.self, from: data) else {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return saved
    }

    private func write(_ cityId: String, _ saved: Saved) {
        guard let data = try? JSONEncoder().encode(saved) else { return }
        try? data.write(to: dir.appendingPathComponent("\(cityId).json"), options: .atomic)
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
