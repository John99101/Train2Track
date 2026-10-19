import Foundation

/// Java's `Math.round` (half up), so iOS rounds delays exactly like Android.
@inline(__always) func javaRound(_ x: Double) -> Int { Int((x + 0.5).rounded(.down)) }

/// One vehicle on one hop: from `fromName` to `toName` between `departureMs` and
/// `arrivalMs`. Its geometry lives in the shape store under `shapeKey`; when that is
/// not known yet the vehicle moves on a straight line between the two stops.
/// Mirror of Android's `LiveSegment` (`data/live/LiveApi.kt`).
public struct LiveSegment: Equatable, Sendable {
    public let tripId: String?
    public let label: String
    public let mode: String
    public let colorHex: String?
    public let fromName: String?
    public let toName: String?
    public let fromLat: Double
    public let fromLon: Double
    public let toLat: Double
    public let toLon: Double
    public let departureMs: Int64
    public let arrivalMs: Int64
    public let delayMinutes: Int?
    public let isRealtime: Bool
    public let shapeKey: String

    public init(tripId: String?, label: String, mode: String, colorHex: String?,
                fromName: String?, toName: String?,
                fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
                departureMs: Int64, arrivalMs: Int64, delayMinutes: Int?, isRealtime: Bool,
                shapeKey: String) {
        self.tripId = tripId; self.label = label; self.mode = mode; self.colorHex = colorHex
        self.fromName = fromName; self.toName = toName
        self.fromLat = fromLat; self.fromLon = fromLon; self.toLat = toLat; self.toLon = toLon
        self.departureMs = departureMs; self.arrivalMs = arrivalMs
        self.delayMinutes = delayMinutes; self.isRealtime = isRealtime; self.shapeKey = shapeKey
    }

    public var hasStopCoordinates: Bool {
        !fromLat.isNaN && !fromLon.isNaN && !toLat.isNaN && !toLon.isNaN
    }

    // NaN != NaN, so compare the coordinates bit for bit.
    public static func == (a: LiveSegment, b: LiveSegment) -> Bool {
        a.tripId == b.tripId && a.label == b.label && a.mode == b.mode && a.colorHex == b.colorHex
            && a.fromName == b.fromName && a.toName == b.toName
            && a.fromLat.bitPattern == b.fromLat.bitPattern && a.fromLon.bitPattern == b.fromLon.bitPattern
            && a.toLat.bitPattern == b.toLat.bitPattern && a.toLon.bitPattern == b.toLon.bitPattern
            && a.departureMs == b.departureMs && a.arrivalMs == b.arrivalMs
            && a.delayMinutes == b.delayMinutes && a.isRealtime == b.isRealtime && a.shapeKey == b.shapeKey
    }
}

/// The Train2Track server's compact live snapshot (format v1, `GET /api/live/{city}`).
/// Segment rows are
/// `[lineIdx, tripId, fromStopIdx, toStopIdx, dep, arr, delaySec|null, realtime 0/1, shapeKey]`
/// with times in seconds relative to `t`. Mirror of Android's `LiveSnapshotDto`.
public struct LiveSnapshot: Sendable {
    public let city: String
    public let t: Int64
    public let stale: Bool
    public let segments: [LiveSegment]

    /// Parses the JSON body and expands it into segments (absolute times, resolved tables).
    public static func parse(_ data: Data) throws -> LiveSnapshot {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.coderReadCorrupt)
        }
        let t = (root["t"] as? NSNumber)?.int64Value ?? 0
        let stops = root["stops"] as? [[Any]] ?? []
        let lines = root["lines"] as? [[Any]] ?? []
        let segs = root["segs"] as? [[Any]] ?? []
        let baseMs = t * 1000

        let stopNames = stops.map { str($0, 0) }
        let stopLats = stops.map { dbl($0, 1) }
        let stopLons = stops.map { dbl($0, 2) }

        var out: [LiveSegment] = []
        out.reserveCapacity(segs.count)
        for s in segs {
            let lineIdx = int(s, 0) ?? -1
            guard lineIdx >= 0, lineIdx < lines.count else { continue }
            let line = lines[lineIdx]
            let from = int(s, 2) ?? -1
            let to = int(s, 3) ?? -1
            guard let dep = int(s, 4), let arr = int(s, 5) else { continue }
            guard let mode = str(line, 1) else { continue }
            let realtime = int(s, 7) == 1
            let delaySec = int(s, 6)
            let label = str(line, 0).flatMap { isBlank($0) ? nil : $0 } ?? "?"
            out.append(LiveSegment(
                tripId: str(s, 1).flatMap { $0.isEmpty ? nil : $0 },
                label: label,
                mode: mode,
                colorHex: str(line, 2).flatMap { isBlank($0) ? nil : $0 },
                fromName: at(stopNames, from) ?? nil,
                toName: at(stopNames, to) ?? nil,
                fromLat: at(stopLats, from) ?? .nan,
                fromLon: at(stopLons, from) ?? .nan,
                toLat: at(stopLats, to) ?? .nan,
                toLon: at(stopLons, to) ?? .nan,
                departureMs: baseMs + Int64(dep) * 1000,
                arrivalMs: baseMs + Int64(arr) * 1000,
                delayMinutes: (realtime && delaySec != nil) ? javaRound(Double(delaySec!) / 60.0) : nil,
                isRealtime: realtime,
                shapeKey: str(s, 8) ?? ""
            ))
        }
        return LiveSnapshot(city: root["city"] as? String ?? "", t: t,
                            stale: (root["stale"] as? Bool) ?? false, segments: out)
    }

    /// `GET /api/shapes/{city}` → `{shapes: {key: encodedPolyline}}`.
    public static func parseShapes(_ data: Data) throws -> [String: String] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.coderReadCorrupt)
        }
        return root["shapes"] as? [String: String] ?? [:]
    }

    /// `GET /api/routes/{city}` → rows `[label, mode, colour hex, polyline5]`.
    public static func parseRoutes(_ data: Data) throws -> [[String]] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.coderReadCorrupt)
        }
        return (root["routes"] as? [[Any]] ?? []).map { row in row.map { ($0 as? String) ?? "" } }
    }

    // MARK: - JSON array helpers (mirror the Kotlin JsonArray extensions)

    private static func isBlank(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private static func at<T>(_ a: [T], _ i: Int) -> T? { i >= 0 && i < a.count ? a[i] : nil }

    private static func str(_ a: [Any], _ i: Int) -> String? {
        guard i < a.count else { return nil }
        switch a[i] {
        case let s as String: return s
        case is NSNull: return nil
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    private static func int(_ a: [Any], _ i: Int) -> Int? {
        guard i < a.count else { return nil }
        if let n = a[i] as? NSNumber {
            let d = n.doubleValue
            return d == d.rounded() ? n.intValue : nil
        }
        if let s = a[i] as? String { return Int(s) }
        return nil
    }

    private static func dbl(_ a: [Any], _ i: Int) -> Double {
        guard i < a.count else { return .nan }
        if let n = a[i] as? NSNumber { return n.doubleValue }
        if let s = a[i] as? String, let d = Double(s) { return d }
        return .nan
    }
}

public extension MapTripSegment {
    /// A raw transitous segment (direct fallback when the server is unreachable) as a
    /// `LiveSegment`; its geometry is added to `shapesOut` under the same key the
    /// server would use. Port of Android's `MapTripSegment.toLiveSegment`.
    func toLiveSegment(shapesOut: inout [String: String]) -> LiveSegment? {
        guard let mode else { return nil }
        guard let depS = departure, let dep = TrainInterpolator.epochMillis(depS) else { return nil }
        guard let arrS = arrival, let arr = TrainInterpolator.epochMillis(arrS) else { return nil }
        if arr <= dep { return nil }
        var key = ""
        if let p = polyline {
            key = GeoPolyline.shapeKey(p)
            shapesOut[key] = p
        }
        let schedDep = scheduledDeparture.flatMap(TrainInterpolator.epochMillis)
        let trip = trips.first
        // Kotlin: substringBefore(' ').takeIf { isNotBlank() } ?: "?"
        let first = trip?.routeShortName.map { name -> String in
            if let space = name.firstIndex(of: " ") { return String(name[..<space]) }
            return name
        }
        let label = first.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } ?? "?"
        return LiveSegment(
            tripId: trip?.tripId,
            label: label,
            mode: mode,
            colorHex: routeColor,
            fromName: from?.name,
            toName: to?.name,
            fromLat: from?.lat ?? .nan,
            fromLon: from?.lon ?? .nan,
            toLat: to?.lat ?? .nan,
            toLon: to?.lon ?? .nan,
            departureMs: dep,
            arrivalMs: arr,
            delayMinutes: (realTime && schedDep != nil) ? javaRound(Double(dep - schedDep!) / 60_000.0) : nil,
            isRealtime: realTime,
            shapeKey: key
        )
    }
}

/// Places vehicles at an instant. Pure, so it can run off the main thread and be
/// tested. Port of `LiveMapViewModel.compute` (Android).
public enum LivePositions {
    /// A lat/lon box (the visible map area plus a margin).
    public struct Box: Equatable, Sendable {
        public let north: Double, south: Double, east: Double, west: Double
        public init(north: Double, south: Double, east: Double, west: Double) {
            self.north = north; self.south = south; self.east = east; self.west = west
        }
        public func expanded(_ factor: Double) -> Box {
            let dLat = (north - south) * factor
            let dLon = (east - west) * factor
            return Box(north: north + dLat, south: south - dLat, east: east + dLon, west: west - dLon)
        }
        public func contains(_ lat: Double, _ lon: Double) -> Bool {
            lat >= south && lat <= north && lon >= west && lon <= east
        }
        public var isReasonable: Bool {
            TrainInterpolator.boundsReasonable(north: north, south: south, east: east, west: west)
        }
    }

    /// The vehicles running at `nowMs`. Without positions (the Feed) every running
    /// vehicle is listed at 0/0; with positions, each is placed along its decoded
    /// track (or a straight line until its geometry is known) and only those inside
    /// `area` are kept.
    public static func compute(_ segments: [LiveSegment], nowMs: Int64, withPositions: Bool,
                               area: Box?, track: (String) -> Track?) -> [TrainPosition] {
        var out: [TrainPosition] = []
        out.reserveCapacity(segments.count)
        for s in segments {
            guard LiveMapConfig.railModes.contains(s.mode), nowMs >= s.departureMs, nowMs <= s.arrivalMs else { continue }
            if !withPositions {
                out.append(train(s, 0, 0))
                continue
            }
            let span = Double(s.arrivalMs - s.departureMs)
            let fraction = span > 0 ? Double(nowMs - s.departureMs) / span : 0
            let p: GeoPoint
            if !s.shapeKey.isEmpty, let t = track(s.shapeKey) {
                p = t.pointAt(fraction)
            } else if s.hasStopCoordinates {
                // Geometry not downloaded yet: straight line between the two stops.
                p = GeoPoint(s.fromLat + (s.toLat - s.fromLat) * fraction,
                             s.fromLon + (s.toLon - s.fromLon) * fraction)
            } else {
                continue
            }
            if let area, !area.contains(p.lat, p.lon) { continue }
            out.append(train(s, p.lat, p.lon))
        }
        return out
    }

    private static func train(_ s: LiveSegment, _ lat: Double, _ lon: Double) -> TrainPosition {
        TrainPosition(latitude: lat, longitude: lon, label: s.label, mode: s.mode, tripId: s.tripId,
                      fromStop: s.fromName, toStop: s.toName, colorHex: s.colorHex,
                      delayMinutes: s.delayMinutes, isRealtime: s.isRealtime)
    }
}

/// Map key of a line route: mode and label, so bus "100" and tram "100" never mix.
public func routeKey(mode: String, label: String) -> String { "\(mode)|\(label)" }

/// Modes that get a route line (ROUTE_MODES on the server). Long distance trains
/// all share one name ("ICE"), so one stored line would be wrong for most of
/// them: they move along their own track without a line. Mirror of Android's
/// `LiveMapViewModel.ROUTE_MODES`.
public let routeModes: Set<String> = ["METRO", "SUBWAY", "TRAM", "BUS", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "RAIL"]

/// True for the `routeKey` of a line that may have a route line.
public func hasRouteLine(_ key: String) -> Bool {
    routeModes.contains(String(key.prefix { $0 != "|" }))
}
