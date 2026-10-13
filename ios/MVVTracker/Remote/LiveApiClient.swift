import Foundation
import MVVTrackerCore

/// The Train2Track server's live data API (Firebase Hosting -> Cloud Function `api`,
/// see functions/lib/liveApi.js). One compact snapshot per city (~140 KB) replaces
/// the ~1.2 MB raw transitous response. Port of Android's `LiveApiService` /
/// `LiveRoutesService`.
struct LiveApiClient {
    static let baseURL = URL(string: "https://example.invalid/api/")!

    private let session: URLSession

    init(session: URLSession = .shared) { self.session = session }

    /// `GET /api/live/{city}`. Fails fast so the app can fall back to transitous.
    func live(_ cityId: String) async throws -> LiveSnapshot {
        try LiveSnapshot.parse(try await get("live/\(cityId)", timeout: 15))
    }

    /// `GET /api/shapes/{city}` → key → encoded polyline.
    func shapes(_ cityId: String) async throws -> [String: String] {
        try LiveSnapshot.parseShapes(try await get("shapes/\(cityId)", timeout: 15))
    }

    /// `GET /api/routes/{city}` → rows `[label, mode, colour, polyline5]`. The first
    /// request for a city builds its routes on the server and may take a minute.
    func routes(_ cityId: String) async throws -> [[String]] {
        try LiveSnapshot.parseRoutes(try await get("routes/\(cityId)", timeout: 70))
    }

    /// `GET /api/route/{city}/{mode}/{label}/{trip}` → one row `[label, mode, colour,
    /// polyline5]`: the route of a line the city's set does not have yet (a long
    /// distance train, a night line). The server builds it once from the journey
    /// `tripId`, which must be running on that line, and keeps it for everybody.
    /// Throws when there is none. Port of Android's `LiveRoutesService.route`.
    func route(_ cityId: String, mode: String, label: String, tripId: String) async throws -> [String] {
        // A "/" would split the path: such a line has no route on the server.
        guard !label.contains("/"), !tripId.contains("/") else { throw URLError(.badURL) }
        let data = try await get("route/\(cityId)/\(mode)/\(label)/\(tripId)", timeout: 40)
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let row = json["route"] as? [String], row.count >= 4, !row[3].isEmpty else {
            throw URLError(.cannotParseResponse)
        }
        return row
    }

    private func get(_ path: String, timeout: TimeInterval) async throws -> Data {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent(path))
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return data
    }
}

/// Where the live data caches live: Application Support (kept, not backed up as user data).
enum LiveCacheDir {
    static func url(_ name: String) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = dir
        try? mutable.setResourceValues(values)
        return dir
    }
}
