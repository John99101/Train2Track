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
