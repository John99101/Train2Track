import Foundation

/// A single train, positioned at the current instant along its route segment.
/// Mirror of `viewmodel/LiveMapViewModel.kt`'s `TrainPosition`.
public struct TrainPosition: Equatable, Identifiable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public let label: String        // e.g. "S1", "U6", "RE5", "ICE"
    public let mode: String         // METRO | SUBWAY | REGIONAL_RAIL | HIGHSPEED_RAIL | ...
    public let tripId: String?      // identifies this exact train run
    public let fromStop: String?    // stop it last left
    public let toStop: String?      // stop it's heading to next
    public let colorHex: String?    // official line colour from the feed (6-hex)
    // Live delay in whole minutes vs the timetable (positive = late, negative =
    // early, 0 = on time). nil when there's no scheduled time to compare against.
    public let delayMinutes: Int?
    // true = MOTIS has a live feed for this trip (position is real-time-backed);
    // false = pure timetable, i.e. "where it should be", not where it is.
    public let isRealtime: Bool
    // Current segment's track geometry — used to draw the route line on the map.
    // Just the current from→to hop, not the whole line.
    public let routePoints: [GeoPoint]?

    /// Stable per-vehicle key (Android's `trainKey`): the trip id, or a label+stops
    /// fallback. Must not contain the position, or every tick would be a new vehicle.
    public var id: String { tripId ?? "\(label)|\(fromStop ?? "null")|\(toStop ?? "null")" }

    public init(latitude: Double, longitude: Double, label: String, mode: String,
                tripId: String? = nil, fromStop: String? = nil, toStop: String? = nil,
                colorHex: String? = nil, delayMinutes: Int? = nil, isRealtime: Bool = false,
                routePoints: [GeoPoint]? = nil) {
        self.latitude = latitude; self.longitude = longitude
        self.label = label; self.mode = mode; self.tripId = tripId
        self.fromStop = fromStop; self.toStop = toStop; self.colorHex = colorHex
        self.delayMinutes = delayMinutes; self.isRealtime = isRealtime
        self.routePoints = routePoints
    }
}

/// Cadence and guard constants — kept identical to `LiveMapViewModel` companion.
public enum LiveMapConfig {
    // Network refetch cadence. A city snapshot from the server is ~140 KB, so it can
    // refresh more often than the old ~1.2 MB direct fetch; a free map box still
    // goes to transitous directly and keeps the slower cadence.
    public static let fetchCityMillis: Int = 20_000
    // The Feed is a list without positions: every 30 s is enough (a third less data).
    public static let fetchFeedMillis: Int = 30_000
    // A free map box goes to transitous directly: wait until the map stopped moving.
    public static let boxSettleMillis: Int = 1_200
    public static let tickMillis: Int = 1_000       // map position recompute; markers glide in between
    public static let listTickMillis: Int = 5_000   // Feed: only which vehicles run changes
    public static let viewportMargin: Double = 0.25 // build markers a bit beyond the screen edge
    public static let windowBeforeMillis: Int = 60_000   // direct transitous time window
    public static let windowAfterMillis: Int = 180_000
    public static let maxSpanDegrees: Double = 1.2

    /// Modes surfaced in the app: rail rapid transit + regional/long-distance,
    /// plus (Phase 3) buses and trams. The Feed narrows to METRO/SUBWAY/BUS/TRAM;
    /// the Map can toggle each mode.
    public static let railModes: Set<String> = [
        "METRO",              // S-Bahn (S1..S8)
        "SUBWAY",             // U-Bahn (U1..U6)
        "TRAM",               // Straßenbahn
        "BUS",                // city/regional buses
        "REGIONAL_RAIL",
        "REGIONAL_FAST_RAIL",
        "HIGHSPEED_RAIL",
        "LONG_DISTANCE",
        "NIGHT_RAIL",
        "RAIL"
    ]
}

public enum TrainInterpolator {

    /// ISO-8601 UTC → epoch milliseconds. transitous emits e.g.
    /// `2026-08-20T15:16:00Z`, occasionally with fractional seconds.
    public static func epochMillis(_ iso: String) -> Int64? {
        if let d = isoNoFraction.date(from: iso) { return Int64(d.timeIntervalSince1970 * 1000) }
        if let d = isoWithFraction.date(from: iso) { return Int64(d.timeIntervalSince1970 * 1000) }
        return nil
    }

    private static let isoNoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let isoWithFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    /// Interpolated position for one segment at `nowMs`, or nil if the segment
    /// is not a rail segment currently underway. Exact port of
    /// `LiveMapViewModel.positionFor`.
    public static func position(for segment: MapTripSegment, nowMs: Int64) -> TrainPosition? {
        guard let mode = segment.mode, LiveMapConfig.railModes.contains(mode) else { return nil }
        guard let depStr = segment.departure, let dep = epochMillis(depStr) else { return nil }
        guard let arrStr = segment.arrival, let arr = epochMillis(arrStr) else { return nil }
        if arr <= dep || nowMs < dep || nowMs > arr { return nil }

        guard let polyline = segment.polyline else { return nil }
        let points = GeoPolyline.decode(polyline)
        if points.isEmpty { return nil }

        let fraction = Double(nowMs - dep) / Double(arr - dep)
        guard let p = GeoPolyline.pointAtFraction(points, fraction) else { return nil }

        let label = segment.trips.first?.routeShortName?
            .split(separator: " ", maxSplits: 1).first.map(String.init)?
            .trimmingCharacters(in: .whitespaces)
        let resolvedLabel = (label?.isEmpty == false) ? label! : "?"

        // Live delay = real-time departure minus the timetable departure (whole
        // minutes). Only meaningful when the trip is real-time-backed.
        var delayMinutes: Int? = nil
        if segment.realTime, let sdStr = segment.scheduledDeparture, let sd = epochMillis(sdStr) {
            delayMinutes = Int((Double(dep - sd) / 60_000.0).rounded())
        }

        return TrainPosition(
            latitude: p.lat,
            longitude: p.lon,
            label: resolvedLabel,
            mode: mode,
            tripId: segment.trips.first?.tripId,
            fromStop: segment.from?.name,
            toStop: segment.to?.name,
            colorHex: segment.routeColor,
            delayMinutes: delayMinutes,
            isRealtime: segment.realTime,
            routePoints: points
        )
    }

    /// All train positions for a set of cached segments at `nowMs`.
    public static func positions(from segments: [MapTripSegment], nowMs: Int64) -> [TrainPosition] {
        segments.compactMap { position(for: $0, nowMs: nowMs) }
    }

    /// Whether a bbox is safe to fetch (span within limits). Port of
    /// `LiveMapViewModel.Bounds.isReasonable`.
    public static func boundsReasonable(north: Double, south: Double,
                                        east: Double, west: Double) -> Bool {
        let latSpan = abs(north - south)
        let lonSpan = abs(east - west)
        let range = 0.0001...LiveMapConfig.maxSpanDegrees
        return range.contains(latSpan) && range.contains(lonSpan)
    }
}
