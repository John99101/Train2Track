import Foundation

/// The server sends a city's live data in three groups (functions/lib/liveGroups.js),
/// so the app downloads only what it shows: the default map needs rail only, about
/// an eighth of the whole city. Mirror of Android's `LiveGroup` (`data/live/LiveGroup.kt`).
public enum LiveGroup: String, CaseIterable, Sendable {
    case rail, tram, bus

    public static let all: Set<LiveGroup> = Set(allCases)

    /// The group of a MOTIS mode, or nil for a mode the app does not show.
    public static func of(_ mode: String) -> LiveGroup? {
        switch mode {
        case "METRO", "SUBWAY", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "HIGHSPEED_RAIL",
             "LONG_DISTANCE", "NIGHT_RAIL", "RAIL":
            return .rail
        case "TRAM":
            return .tram
        case "BUS":
            return .bus
        default:
            return nil
        }
    }

    /// The groups needed to show `modes` (nil = every mode).
    public static func forModes(_ modes: Set<String>?) -> Set<LiveGroup> {
        guard let modes else { return all }
        return Set(modes.compactMap { of($0) })
    }

    /// The groups' segments as one list, always in the same order (rail, tram, bus).
    public static func merged(_ parts: [LiveGroup: [LiveSegment]]) -> [LiveSegment] {
        allCases.flatMap { parts[$0] ?? [] }
    }
}
