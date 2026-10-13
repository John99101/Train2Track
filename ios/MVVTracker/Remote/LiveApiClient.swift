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
