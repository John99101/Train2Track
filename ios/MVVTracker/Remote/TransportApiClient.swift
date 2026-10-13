import Foundation
import MVVTrackerCore

/// Live map data source: **transitous** (https://transitous.org), a community-run
/// open MOTIS instance. No API key. Swift port of `TransportApiService.kt`
/// (Retrofit) using URLSession. DTOs live in MVVTrackerCore.
///
/// The app only asks transitous itself when the Train2Track server does not answer
/// or when the map shows an area outside the supported cities. It asks for nothing
/// else: line routes come from the Train2Track server only (`LiveApiClient.route`).
protocol TransportApi {
    func mapTrips(min: String, max: String, zoom: Int,
                  startTime: String, endTime: String) async throws -> [MapTripSegment]
}

struct TransportApiClient: TransportApi {
    static let baseURL = URL(string: "https://api.transitous.org")!

    private let session: URLSession
    private let decoder = JSONDecoder()

    init(session: URLSession = .shared) {
        self.session = session
    }

    func mapTrips(min: String, max: String, zoom: Int,
                  startTime: String, endTime: String) async throws -> [MapTripSegment] {
        let url = try makeURL(path: "api/v1/map/trips", query: [
            "min": min, "max": max, "zoom": String(zoom),
            "startTime": startTime, "endTime": endTime
        ])
        return try await get(url, as: [MapTripSegment].self)
    }

    // MARK: - Plumbing

    private func makeURL(path: String, query: [String: String]) throws -> URL {
        guard var comps = URLComponents(url: Self.baseURL.appendingPathComponent(path),
                                        resolvingAgainstBaseURL: false) else {
            throw URLError(.badURL)
        }
        comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let url = comps.url else { throw URLError(.badURL) }
        return url
    }

    private func get<T: Decodable>(_ url: URL, as type: T.Type) async throws -> T {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        // Transitous asks every app to say who it is: name, version and a contact.
        request.setValue(AppInfo.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw HTTPStatusError(status: http.statusCode)
        }
        return try decoder.decode(T.self, from: data)
    }
}

/// transitous answered with an error status (422 = too many trips for the area).
struct HTTPStatusError: Error {
    let status: Int
}

/// ISO-8601 UTC timestamps for the transitous time window (now ± 120 s).
enum TransportTime {
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    static func iso(_ date: Date) -> String { formatter.string(from: date) }
}
