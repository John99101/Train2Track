import Foundation

/// A station the rider found or opened. `modes` as the timetable names them
/// (SUBURBAN, SUBWAY, ...). Port of Android's `StationRef`.
public struct StationRef: Equatable, Hashable, Identifiable, Sendable, Codable {
    public let id: String
    public let name: String
    public let cityId: String
    public let modes: [String]

    public init(id: String, name: String, cityId: String, modes: [String] = []) {
        self.id = id
        self.name = name
        self.cityId = cityId
        self.modes = modes
    }

    /// The app's mode symbols for the station, each once (S-Bahn, U-Bahn, tram, bus, other trains).
    public var symbolModes: [String] {
        let mapped = Set(modes.compactMap { m -> String? in
            switch m {
            case "SUBURBAN", "METRO": return "METRO"
            case "SUBWAY": return "SUBWAY"
            case "TRAM": return "TRAM"
            case "BUS": return "BUS"
            case "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "RAIL": return "REGIONAL_RAIL"
            default: return nil
            }
        })
        return ["METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL"].filter(mapped.contains)
    }
}

/// One departure on a station's board. Port of Android's `Departure`.
public struct Departure: Equatable, Identifiable, Sendable {
    public let label: String
    /// The same mode names as the live data (METRO = S-Bahn, SUBWAY = U-Bahn, ...).
    public let mode: String
    public let colorHex: String
    public let destination: String
    public let plannedAtMs: Int64
    public let expectedAtMs: Int64
    public let platform: String
    public let realtime: Bool
    public let cancelled: Bool
    public let tripId: String

    public var id: String { "\(tripId)|\(plannedAtMs)|\(label)" }

    /// Minutes late (0 when on time or without live data).
    public var delayMinutes: Int { max(0, Int((expectedAtMs - plannedAtMs) / 60_000)) }
}

/// A station dot on the map ("Stations" button). Port of Android's `MapStation`.
public struct MapStation: Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let lat: Double
    public let lon: Double
    /// The map buttons of the lines that stop here (`MapGroups`).
    public let bits: Int

    public init(id: String, name: String, lat: Double, lon: Double, bits: Int) {
        self.id = id
        self.name = name
        self.lat = lat
        self.lon = lon
        self.bits = bits
    }
}

/// One station's board.
public struct StationBoard: Equatable, Sendable {
    public let name: String
    public let departures: [Departure]
    public let loadedAtMs: Int64
}

/// Parsing of the server's station answers (functions/lib/stations.js) and the
/// search text rule. Port of Android's `StationApi.kt` helpers.
public enum StationParser {
    /// The search text as the server takes it: trimmed, single spaces, lower case;
    /// nil when shorter than 2 or longer than 40 characters.
    public static func normalizeQuery(_ text: String) -> String? {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .lowercased()
        return (2...40).contains(q.count) ? q : nil
    }

    /// `{ stations: [[id, name, modes]] }`
    public static func stations(_ data: Data, cityId: String) throws -> [StationRef] {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = json["stations"] as? [[Any]] else { throw URLError(.cannotParseResponse) }
        return rows.compactMap { row in
            guard row.count >= 2, let id = row[0] as? String, let name = row[1] as? String,
                  !id.isEmpty, !name.isEmpty else { return nil }
            let modes = (row.count > 2 ? row[2] as? String : nil)?.split(separator: ",").map(String.init) ?? []
            return StationRef(id: id, name: name, cityId: cityId, modes: modes)
        }
    }

    /// `{ complete, stations: [[name, lat, lon, bits, id]] }` (GET /api/stops/{city}, or
    /// the copy shipped with the app): the station dots and whether the server's list
    /// is complete (when not, the app asks again sooner).
    public static func mapStations(_ data: Data) throws -> (stations: [MapStation], complete: Bool) {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = json["stations"] as? [[Any]] else { throw URLError(.cannotParseResponse) }
        let stations: [MapStation] = rows.compactMap { row in
            guard row.count >= 5, let name = row[0] as? String, let lat = (row[1] as? NSNumber)?.doubleValue,
                  let lon = (row[2] as? NSNumber)?.doubleValue, let bits = (row[3] as? NSNumber)?.intValue,
                  let id = row[4] as? String, !name.isEmpty, !id.isEmpty, bits != 0 else { return nil }
            return MapStation(id: id, name: name, lat: lat, lon: lon, bits: bits)
        }
        return (stations, json["complete"] as? Bool ?? true)
    }

    /// `{ t, name, deps: [[label, mode, colour, destination, planned, expected, platform, flags, tripId]] }`
    public static func board(_ data: Data, nowMs: Int64) throws -> StationBoard {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }
        let baseMs = ((json["t"] as? NSNumber)?.int64Value ?? 0) * 1000
        func str(_ row: [Any], _ i: Int) -> String { i < row.count ? (row[i] as? String ?? "") : "" }
        func num(_ row: [Any], _ i: Int) -> Int64? { i < row.count ? (row[i] as? NSNumber)?.int64Value : nil }
        var seen = Set<String>()
        let departures: [Departure] = ((json["deps"] as? [[Any]]) ?? []).compactMap { row in
            guard let expected = num(row, 5) else { return nil }
            let planned = num(row, 4) ?? expected
            let flags = num(row, 7) ?? 0
            let label = str(row, 0)
            let dep = Departure(
                label: label.isEmpty ? "?" : label,
                mode: str(row, 1),
                colorHex: str(row, 2),
                destination: str(row, 3),
                plannedAtMs: baseMs + planned * 1000,
                expectedAtMs: baseMs + expected * 1000,
                platform: str(row, 6),
                realtime: flags & 1 != 0,
                cancelled: flags & 2 != 0,
                tripId: str(row, 8)
            )
            // Each journey once (the list identifies rows by it).
            return seen.insert(dep.id).inserted ? dep : nil
        }
        return StationBoard(name: json["name"] as? String ?? "", departures: departures, loadedAtMs: nowMs)
    }
}
