import Foundation
import Combine
import MVVTrackerCore

/// The city shown in the Feed / centred on the Map. Shared and persisted so the
/// choice survives tab switches and app relaunches (a per-view `@State` would
/// reset to München every time the Feed is recreated).
///
/// Precedence: a city the user picks manually wins and is never overridden by
/// location; otherwise the nearest city from the user's location is used; failing
/// that, München (the default).
@MainActor
final class CitySelection: ObservableObject {
    static let shared = CitySelection()

    @Published private(set) var city: GermanCity
    /// True once the user has chosen a city from the picker — location no longer
    /// overrides it.
    @Published private(set) var userPicked: Bool

    private let d = UserDefaults.standard
    private enum Keys {
        static let city = "selected_city_name"
        static let picked = "user_picked_city"
    }

    private init() {
        let savedName = d.string(forKey: Keys.city)
        city = germanCities.first { $0.name == savedName } ?? defaultCity
        userPicked = d.bool(forKey: Keys.picked)
    }

    /// The user chose a city from the picker — this sticks.
    func pick(_ newCity: GermanCity) {
        city = newCity
        userPicked = true
        d.set(newCity.name, forKey: Keys.city)
        d.set(true, forKey: Keys.picked)
    }

    /// A city derived from the user's location. Applied only when the user hasn't
    /// manually picked one (so a manual choice is never reverted).
    func suggestFromLocation(_ nearest: GermanCity) {
        guard !userPicked, nearest != city else { return }
        city = nearest
        d.set(nearest.name, forKey: Keys.city)
    }
}
