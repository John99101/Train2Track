import Foundation

/// How close the map is. It decides the size of the vehicle badges: full badge,
/// small badge, or a dot without a label. Port of Android's `ZoomTier`
/// (`ui/screens/MapStyle.kt`).
public enum BadgeTier: Int, Sendable {
    case far, mid, near
}

/// The badge size for a map that shows `longitudeSpan` degrees from left to right.
///
/// Android switches at zoom 11.5 and 13.5 (OpenStreetMap). On a phone that is a map
/// about 0.52 and 0.13 degrees of longitude wide, so both apps switch at the same view.
public func badgeTier(longitudeSpan: Double) -> BadgeTier {
    if longitudeSpan <= nearMaxLongitudeSpan { return .near }
    if longitudeSpan <= midMaxLongitudeSpan { return .mid }
    return .far
}

public let nearMaxLongitudeSpan = 0.131
public let midMaxLongitudeSpan = 0.524
/// Bus stops get a dot from this width on (Android: zoom 14.5).
public let busStopMaxLongitudeSpan = 0.0655

/// The map's filter buttons as bits, the same as the server's MAP_GROUP_BITS and
/// Android's `MapGroups`: a station carries the buttons of the lines that stop there.
public enum MapGroups {
    public static let sbahn = 1
    public static let ubahn = 2
    public static let tram = 4
    public static let bus = 8
    public static let rail = 16

    /// The modes for a station's symbols (the timetable's names, as in `StationRef.modes`).
    public static func modes(_ bits: Int) -> [String] {
        var out: [String] = []
        if bits & sbahn != 0 { out.append("SUBURBAN") }
        if bits & ubahn != 0 { out.append("SUBWAY") }
        if bits & tram != 0 { out.append("TRAM") }
        if bits & bus != 0 { out.append("BUS") }
        if bits & rail != 0 { out.append("REGIONAL_RAIL") }
        return out
    }
}

/// The station kinds (`MapGroups` bits) that get a dot on a map `longitudeSpan`
/// degrees wide: rail stations from a city view on, tram stops closer, bus stops
/// only on a few streets' view (there are thousands). Port of Android's `stationMaskFor`.
public func stationMask(longitudeSpan: Double) -> Int {
    var mask = 0
    if longitudeSpan <= midMaxLongitudeSpan { mask |= MapGroups.sbahn | MapGroups.ubahn | MapGroups.rail }
    if longitudeSpan <= nearMaxLongitudeSpan { mask |= MapGroups.tram }
    if longitudeSpan <= busStopMaxLongitudeSpan { mask |= MapGroups.bus }
    return mask
}

/// Draw order of the vehicles, lowest first: bus, tram, rail, S-Bahn and U-Bahn. A
/// reported vehicle is always on top. Port of Android's `drawRank`.
public func drawRank(mode: String, flagged: Bool = false) -> Int {
    if flagged { return 4 }
    switch mode {
    case "METRO", "SUBWAY": return 3
    case "TRAM": return 1
    case "BUS": return 0
    default: return 2
    }
}
