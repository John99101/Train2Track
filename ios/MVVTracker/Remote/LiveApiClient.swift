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

    /// `GET /api/live/{city}`, the whole city (a server not updated yet knows only
    /// this one). Fails fast so the app can fall back to transitous.
    func live(_ cityId: String) async throws -> LiveSnapshot {
        try LiveSnapshot.parse(try await get("live/\(cityId)", timeout: 15, viaCdn: true))
    }

    /// What `liveGroup` got back.
    enum GroupAnswer: Sendable {
        /// New data and its ETag.
        case data(LiveSnapshot, etag: String?)
        /// 304: nothing changed since the data with the ETag that was sent.
        case notModified
        /// 404: a server not updated yet knows only the whole city (`live`).
        case unknownPath
    }

    /// `GET /api/live/{city}/{group}`: one `LiveGroup` of the city. With the ETag of
    /// the data the app already has, the server answers 304 as long as nothing
    /// changed. Done by hand, not through `URLCache` (it silently skips large
    /// answers), so both apps behave the same. Port of Android's `liveGroup`.
    func liveGroup(_ cityId: String, group: LiveGroup, etag: String?) async throws -> GroupAnswer {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent("live/\(cityId)/\(group.rawValue)"))
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await sendLive(request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        switch http.statusCode {
        case 304:
            return .notModified
        case 404:
            return .unknownPath
        case 200...299:
            return .data(try LiveSnapshot.parse(data), etag: http.value(forHTTPHeaderField: "ETag"))
        default:
            throw URLError(.badServerResponse)
        }
    }

    /// `GET /api/shapes/{city}` → key → encoded polyline.
    func shapes(_ cityId: String) async throws -> [String: String] {
        try LiveSnapshot.parseShapes(try await get("shapes/\(cityId)", timeout: 15))
    }

    /// What `routes` got back.
    enum RoutesAnswer: Sendable {
        /// The set, rows `[label, mode, colour, piece, piece...]`, and its ETag.
        case data([[String]], etag: String?)
        /// 304: the set has not changed since the one with the ETag that was sent.
        case notModified
    }

    /// `GET /api/routes/{city}`. With the ETag of the copy on the phone the server
    /// answers 304 without a body when nothing changed. The first request for a city
    /// builds its routes on the server and may take a minute.
    func routes(_ cityId: String, etag: String?) async throws -> RoutesAnswer {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent("routes/\(cityId)"))
        request.timeoutInterval = 70
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        switch http.statusCode {
        case 304:
            return .notModified
        case 200...299:
            return .data(try LiveSnapshot.parseRoutes(data), etag: http.value(forHTTPHeaderField: "ETag"))
        default:
            throw URLError(.badServerResponse)
        }
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

    private func get(_ path: String, timeout: TimeInterval, viaCdn: Bool = false) async throws -> Data {
        var request = URLRequest(url: Self.baseURL.appendingPathComponent(path))
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data
        let response: URLResponse
        if viaCdn {
            (data, response) = try await sendLive(request)
        } else {
            (data, response) = try await session.data(for: request)
        }
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return data
    }

    /// Sends a live data request through the CDN when one is set (see `LiveHost`),
    /// and to the server itself when the CDN fails: no connection, or an answer of
    /// 500 and above. Then the CDN is left alone for ten minutes.
    private func sendLive(_ request: URLRequest) async throws -> (Data, URLResponse) {
        guard let cdn = LiveHost.shared.current(), let url = request.url,
              LiveHostRule.isLivePath(url.path),
              var parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return try await session.data(for: request)
        }
        parts.scheme = cdn.scheme
        parts.host = cdn.host
        parts.port = cdn.port
        if let cdnURL = parts.url {
            var viaCdn = request
            viaCdn.url = cdnURL
            do {
                let (data, response) = try await session.data(for: viaCdn)
                if let http = response as? HTTPURLResponse, http.statusCode < 500 { return (data, response) }
            } catch let error as URLError where error.code == .cancelled {
                throw error
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // Falls through to the server below.
            }
            LiveHost.shared.failed()
        }
        return try await session.data(for: request)
    }
}

/// Where the live vehicle data is loaded from: the Train2Track server itself, or a
/// CDN in front of it (KeyCDN) once the owner sets its address in Firestore
/// (document config/app, text field liveCdn), read once per start by `UpdateGate`.
/// After the CDN failed it is left alone for ten minutes. Port of Android's
/// `LiveHost`; the rule which addresses are taken is `LiveHostRule` (core).
final class LiveHost: @unchecked Sendable {
    static let shared = LiveHost()
    private let lock = NSLock()
    private var cdn: URL?
    private var pausedUntil = Date.distantPast

    func configure(_ value: String?) {
        lock.withLock {
            cdn = LiveHostRule.cdnBase(value)
            pausedUntil = .distantPast
        }
    }

    /// The CDN to use now, or nil for the server itself.
    func current(now: Date = Date()) -> URL? {
        lock.withLock { now >= pausedUntil ? cdn : nil }
    }

    func failed(now: Date = Date()) {
        lock.withLock { pausedUntil = now.addingTimeInterval(LiveHostRule.pause) }
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
