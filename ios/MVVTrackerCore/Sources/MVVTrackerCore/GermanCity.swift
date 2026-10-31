import Foundation

/// Deutsche Bahn ticket shop — nationwide default when a city has no local operator.
public let dbTicketsURL = "https://www.bahn.de/"

/// Half-size (degrees) of a city's region: large enough to cover the WHOLE S-Bahn /
/// U-Bahn network around the city, not just the centre. The server uses the same
/// region per city (functions/lib/cities.js). Mirror of `CITY_HALF_LAT/LON`.
public let cityHalfLat = 0.35
public let cityHalfLon = 0.50

/// A German city with an S-Bahn and U-Bahn network, used by the Feed screen's
/// city picker. Coordinates are the city centre the live map is centred on.
/// `id` is the city's key on the Train2Track server (`/api/live/{id}`).
/// `ticketURL` is the local operator's ticket page (opens their app if installed,
/// else the browser); it defaults to Deutsche Bahn. Mirror of `GermanCity.kt`.
public struct GermanCity: Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let latitude: Double
    public let longitude: Double
    public let ticketURL: String

    public init(id: String, name: String, latitude: Double, longitude: Double, ticketURL: String = dbTicketsURL) {
        self.id = id
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.ticketURL = ticketURL
    }

    public var north: Double { latitude + cityHalfLat }
    public var south: Double { latitude - cityHalfLat }
    public var east: Double { longitude + cityHalfLon }
    public var west: Double { longitude - cityHalfLon }

    /// True when the box lies completely inside this city's region.
    public func regionContains(north: Double, south: Double, east: Double, west: Double) -> Bool {
        north <= self.north && south >= self.south && east <= self.east && west >= self.west
    }
}

/// Cities offered in the Feed picker. First entry (München) is the default.
/// Ticket URLs point at the local operator's ticket page (verify/refine as needed;
/// anything without a local override falls back to Deutsche Bahn).
public let germanCities: [GermanCity] = [
    GermanCity(id: "muenchen", name: "München", latitude: 48.1372, longitude: 11.5755, ticketURL: "https://www.mvg.de/abos-tickets.html"),
    GermanCity(id: "berlin", name: "Berlin", latitude: 52.5200, longitude: 13.4050, ticketURL: "https://www.bvg.de/de/abos-und-tickets/tickets-kaufen"),
    GermanCity(id: "hamburg", name: "Hamburg", latitude: 53.5511, longitude: 9.9937, ticketURL: "https://www.hvv.de/de/onlineshop"),
    GermanCity(id: "frankfurt", name: "Frankfurt", latitude: 50.1109, longitude: 8.6821, ticketURL: "https://www.rmv.de/c/de/tickets"),
    GermanCity(id: "nuernberg", name: "Nürnberg", latitude: 49.4521, longitude: 11.0767, ticketURL: "https://www.vgn.de/tickets/"),
    GermanCity(id: "stuttgart", name: "Stuttgart", latitude: 48.7758, longitude: 9.1829, ticketURL: "https://www.vvs.de/tickets-und-abos/einzelticket"),
    GermanCity(id: "koeln", name: "Köln", latitude: 50.9375, longitude: 6.9603, ticketURL: "https://www.kvb.koeln/abos-und-tickets/alle-tickets/index.html"),
    GermanCity(id: "duesseldorf", name: "Düsseldorf", latitude: 51.2277, longitude: 6.7735, ticketURL: "https://www.rheinbahn.de/tickets-und-tarife"),
    GermanCity(id: "dortmund", name: "Dortmund", latitude: 51.5136, longitude: 7.4653, ticketURL: "https://www.bus-und-bahn.de/tickets"),
    GermanCity(id: "leipzig", name: "Leipzig", latitude: 51.3397, longitude: 12.3731, ticketURL: "https://www.mdv.de/tickets-tarif/tickets/alle-tickets"),
    GermanCity(id: "hannover", name: "Hannover", latitude: 52.3759, longitude: 9.7320, ticketURL: "https://www.uestra.de/fahrkarten-preise/")
]

/// Cities prepared for a later release and not shown anywhere yet: nothing in the
/// app reads this list. To make one public, move it into `germanCities` (Android:
/// HIDDEN_CITIES to GERMAN_CITIES) and remove hidden: true in functions/lib/cities.js.
/// A new city also needs its id in validCity in firestore.rules. Leipzig and
/// Hannover went public in 0.6.0; none is waiting now. Mirror of `HIDDEN_CITIES`.
public let hiddenCities: [GermanCity] = []

/// The supported city whose region fully contains the box, preferring the one whose
/// centre is closest to the box centre (regions overlap in the Ruhr area); nil when
/// the box is outside every region (or bigger than one). Port of `cityForBox`.
public func cityForBox(north: Double, south: Double, east: Double, west: Double) -> GermanCity? {
    let cLat = (north + south) / 2.0
    let cLon = (east + west) / 2.0
    var best: GermanCity?
    var bestD = Double.infinity
    for c in germanCities where c.regionContains(north: north, south: south, east: east, west: west) {
        let d = (c.latitude - cLat) * (c.latitude - cLat) + (c.longitude - cLon) * (c.longitude - cLon)
        if d < bestD { best = c; bestD = d }   // strict <: keeps the first of equals, like minByOrNull
    }
    return best
}

/// The city the map shows. Normally the one whose region fully contains the visible
/// box (`cityForBox`). A box that sticks out of every region (the map is zoomed out,
/// or moved towards the edge of the network) still belongs to the city its centre
/// lies in: the city's vehicles and route lines stay while the city is on screen, and
/// the app does not have to ask the timetable service itself. Only a view whose
/// centre is outside every region is a free map area; nil then.
/// Port of `cityForView`.
public func cityForView(north: Double, south: Double, east: Double, west: Double) -> GermanCity? {
    if let city = cityForBox(north: north, south: south, east: east, west: west) { return city }
    let cLat = (north + south) / 2.0
    let cLon = (east + west) / 2.0
    var best: GermanCity?
    var bestD = Double.infinity
    for c in germanCities where cLat >= c.south && cLat <= c.north && cLon >= c.west && cLon <= c.east {
        let d = (c.latitude - cLat) * (c.latitude - cLat) + (c.longitude - cLon) * (c.longitude - cLon)
        if d < bestD { best = c; bestD = d }   // strict <: keeps the first of equals, like minByOrNull
    }
    return best
}

/// The default city used when no location is available (München).
public let defaultCity: GermanCity = germanCities[0]

public enum CityLocator {
    /// Farthest a fix may be from a city and still count as "near" it.
    /// Mirror of `LocationCityProvider.MAX_KM`.
    public static let maxKm: Double = 60.0

    /// Great-circle distance in km (haversine). Port of
    /// `LocationCityProvider.distanceKm`.
    public static func distanceKm(_ lat1: Double, _ lon1: Double,
                                  _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6371.0
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLon = (lon2 - lon1) * .pi / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(a.squareRoot(), (1 - a).squareRoot())
    }

    /// Nearest supported city to `(lat, lon)` within `maxKm`, else nil.
    /// Port of `LocationCityProvider.nearestCity`'s selection step.
    public static func nearestCity(lat: Double, lon: Double) -> GermanCity? {
        germanCities
            .min { distanceKm($0.latitude, $0.longitude, lat, lon)
                 < distanceKm($1.latitude, $1.longitude, lat, lon) }
            .flatMap { city in
                distanceKm(city.latitude, city.longitude, lat, lon) <= maxKm ? city : nil
            }
    }
}

// MARK: - Reports per city
//
// Every report, delay vote and log entry carries the city the train was seen in,
// and each app only loads the reports of the cities it is showing (instead of
// every report in Germany). Mirror of the same section in Android's
// `GermanCity.kt`; the ids are also listed in `firestore.rules`.

/// A train outside every supported city (a map view somewhere else). Mirror of `CITY_OTHER`.
public let cityOther = "other"

/// Set by the server for app versions that send no city; every app loads these too.
/// Mirror of `CITY_UNKNOWN`.
public let cityUnknown = "unknown"

/// Regions this close (degrees) still count as touching: a train can be a little
/// outside its city's box.
private let reportScopeMargin = 0.15

/// The city a train at this position is filed under: the supported city whose
/// region contains it (the nearest centre where regions overlap), else `cityOther`.
/// Port of `reportCityAt`.
public func reportCity(latitude: Double, longitude: Double) -> String {
    var best: GermanCity?
    var bestD = Double.infinity
    for c in germanCities
    where latitude >= c.south && latitude <= c.north && longitude >= c.west && longitude <= c.east {
        let d = (c.latitude - latitude) * (c.latitude - latitude)
            + (c.longitude - longitude) * (c.longitude - longitude)
        if d < bestD { best = c; bestD = d }
    }
    return best?.id ?? cityOther
}

/// Ids of the cities whose region touches the box.
private func citiesTouching(north: Double, south: Double, east: Double, west: Double) -> [String] {
    germanCities
        .filter {
            $0.south - reportScopeMargin <= north && $0.north + reportScopeMargin >= south
                && $0.west - reportScopeMargin <= east && $0.east + reportScopeMargin >= west
        }
        .map(\.id)
}

/// The report cities an app showing `city` must load: the city itself, every city
/// whose region overlaps it (the same train can be filed under either, as in the
/// Ruhr area) and `cityUnknown`. Sorted. Port of `reportScopeFor(city)`.
public func reportScope(for city: GermanCity) -> [String] {
    (citiesTouching(north: city.north, south: city.south, east: city.east, west: city.west)
        + [cityUnknown]).sorted()
}

/// Same for a free map box: every city it touches, `cityOther` and `cityUnknown`.
public func reportScope(north: Double, south: Double, east: Double, west: Double) -> [String] {
    (citiesTouching(north: north, south: south, east: east, west: west)
        + [cityOther, cityUnknown]).sorted()
}
