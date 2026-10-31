import Foundation
import MVVTrackerCore

/// The Train2Track server's station API (functions/lib/stations.js). Nothing is
/// loaded in advance: a search when the rider types, one station's departures when
/// the rider opens it, the station dots (shipped with the app, checked weekly while
/// the map's "Stations" button is on). Port of Android's `StationApiService` /
/// `StationRepository`.
struct StationApiClient {
    private let session: URLSession

    init(session: URLSession = .shared) { self.session = session }

    /// Stations of the city whose name matches `text` (2 to 40 characters).
    func search(cityId: String, text: String) async throws -> [StationRef] {
        // A "/" would split the path: the server sees it as a space anyway.
        guard let q = StationParser.normalizeQuery(text.replacingOccurrences(of: "/", with: " ")) else { return [] }
        return try StationParser.stations(try await get(["stations", cityId, q]), cityId: cityId)
    }

    /// The next departures at the station.
    func board(_ station: StationRef) async throws -> StationBoard {
        let data = try await get(["station", station.cityId, station.id])
        return try StationParser.board(data, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    /// What `mapStations` got back.
    enum MapStationsAnswer: Sendable {
        /// The answer (see `StationParser.mapStations`) and its ETag.
        case data(Data, etag: String?)
        /// 304: nothing changed since the copy with the ETag that was sent.
        case notModified
    }

    /// `GET /api/stops/{city}`: the city's stations for the map's "Stations" button.
    /// With the ETag of the copy on the phone the server answers 304 without a body
    /// when nothing changed.
    func mapStations(cityId: String, etag: String?) async throws -> MapStationsAnswer {
        var request = URLRequest(url: LiveApiClient.baseURL.appendingPathComponent("stops").appendingPathComponent(cityId))
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        switch http.statusCode {
        case 304: return .notModified
        case 200...299: return .data(data, etag: http.value(forHTTPHeaderField: "ETag"))
        default: throw URLError(.badServerResponse)
        }
    }

    private func get(_ parts: [String]) async throws -> Data {
        var url = LiveApiClient.baseURL
        for part in parts { url.appendPathComponent(part) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }
}
