import Foundation
import MVVTrackerCore

/// The Train2Track server's station API (functions/lib/stations.js). Nothing is
/// loaded in advance and nothing goes on the map: a search when the rider types,
/// one station's departures when the rider opens it. Port of Android's
/// `StationApiService` / `StationRepository`.
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
