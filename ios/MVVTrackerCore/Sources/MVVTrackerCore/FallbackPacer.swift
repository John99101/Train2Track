import Foundation

/// Decides when the app may ask our server and when it may ask transitous directly
/// instead (the fallback). The point: when our server is down, thousands of phones
/// must not all hit the community run transitous server every 20 s.
///
///  - Our server answered badly or not in time: it is left alone for
///    `serverBackoffMs`; meanwhile the fallback is used.
///  - The phone itself has no connection: no back off, the server is simply tried
///    again at the next refresh (so the app recovers quickly after a tunnel).
///  - transitous directly: at most one request per city every `directMinIntervalMs`,
///    whether it worked or not.
///
/// Pure logic with the time passed in. 1:1 port of Android's `FallbackPacer.kt`.
public struct FallbackPacer: Sendable {
    public static let serverBackoffMs: Int64 = 2 * 60_000
    public static let directMinIntervalMs: Int64 = 2 * 60_000

    private var serverRetryAtMs: Int64 = 0
    private var directRetryAtMs: [String: Int64] = [:]

    public init() {}

    public func serverAllowed(nowMs: Int64) -> Bool { nowMs >= serverRetryAtMs }

    public mutating func onServerFailure(nowMs: Int64, deviceOffline: Bool) {
        if !deviceOffline { serverRetryAtMs = nowMs + Self.serverBackoffMs }
    }

    public func directAllowed(_ cityId: String, nowMs: Int64) -> Bool {
        nowMs >= (directRetryAtMs[cityId] ?? 0)
    }

    /// Call right before a direct request for `cityId`.
    public mutating func onDirectRequest(_ cityId: String, nowMs: Int64) {
        directRetryAtMs[cityId] = nowMs + Self.directMinIntervalMs
    }
}

public extension LivePositions.Box {
    /// True when `other` lies completely inside this box.
    func contains(_ other: LivePositions.Box) -> Bool {
        other.north <= north && other.south >= south && other.east <= east && other.west >= west
    }

    /// Grown by `factor` of its size on every side, and to at least the given half
    /// sizes. Port of Android's `LiveTripsRepository.Area.grown`.
    func grown(factor: Double, minHalfLat: Double, minHalfLon: Double) -> LivePositions.Box {
        let halfLat = max((north - south) / 2.0 * (1.0 + 2.0 * factor), minHalfLat)
        let halfLon = max((east - west) / 2.0 * (1.0 + 2.0 * factor), minHalfLon)
        let lat = (north + south) / 2.0
        let lon = (east + west) / 2.0
        return LivePositions.Box(north: lat + halfLat, south: lat - halfLat,
                                 east: lon + halfLon, west: lon - halfLon)
    }
}
