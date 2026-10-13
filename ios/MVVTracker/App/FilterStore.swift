import Foundation
import Combine

/// The user's view filters, kept for the whole app and across restarts: the Feed
/// filters (modes, current reports, one line) and the Map's mode filter. Held in
/// one place (not inside each screen) so they survive leaving a screen and closing
/// the app. Stored locally in UserDefaults only; no personal data. "Delete my
/// data" clears them. Port of Android's `FilterStore.kt` (the city itself is kept
/// by `CitySelection`).
@MainActor
final class FilterStore: ObservableObject {
    static let shared = FilterStore()

    /// Selected Feed filters by name (sbahn, ubahn, bus, tram, reports); empty = all.
    @Published private(set) var feedModes: Set<String>
    /// The single line picked in the Feed, valid only in `feedLineCity` ("S1" is
    /// another line elsewhere).
    @Published private(set) var feedLine: String?
    @Published private(set) var feedLineCity: String?
    /// Modes shown on the Map by name; nil = the default selection.
    @Published private(set) var mapModes: Set<String>?

    private let d = UserDefaults.standard
    private enum Keys {
        static let feedModes = "filter_feed_modes"
        static let feedLine = "filter_feed_line"
        static let feedLineCity = "filter_feed_line_city"
        static let mapModes = "filter_map_modes"
    }

    private init() {
        feedModes = Set(d.stringArray(forKey: Keys.feedModes) ?? [])
        feedLine = d.string(forKey: Keys.feedLine)
        feedLineCity = d.string(forKey: Keys.feedLineCity)
        mapModes = d.stringArray(forKey: Keys.mapModes).map { Set($0) }
    }

    /// The Feed's line filter while `cityId` is shown, else nil (all lines).
    func feedLine(in cityId: String) -> String? {
        feedLineCity == cityId ? feedLine : nil
    }

    func setFeedModes(_ names: Set<String>) {
        feedModes = names
        d.set(Array(names), forKey: Keys.feedModes)
    }

    /// `line` = nil shows all lines again.
    func setFeedLine(cityId: String, line: String?) {
        feedLine = line
        feedLineCity = line == nil ? nil : cityId
        if let line {
            d.set(line, forKey: Keys.feedLine)
            d.set(cityId, forKey: Keys.feedLineCity)
        } else {
            d.removeObject(forKey: Keys.feedLine)
            d.removeObject(forKey: Keys.feedLineCity)
        }
    }

    func setMapModes(_ names: Set<String>) {
        mapModes = names
        d.set(Array(names), forKey: Keys.mapModes)
    }

    /// "Delete my data".
    func clear() {
        feedModes = []
        feedLine = nil
        feedLineCity = nil
        mapModes = nil
        for key in [Keys.feedModes, Keys.feedLine, Keys.feedLineCity, Keys.mapModes] {
            d.removeObject(forKey: key)
        }
    }
}
