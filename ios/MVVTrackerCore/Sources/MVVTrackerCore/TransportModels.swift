import Foundation

/// Codable DTOs for the **transitous** (MOTIS) live-map API.
/// Mirror of `data/remote/TransportApiService.kt`. All fields optional and all
/// arrays default to empty so a partial/odd payload never fails decoding.

public struct MapPlace: Codable, Equatable, Sendable {
    public let name: String?
    public let lat: Double?
    public let lon: Double?

    public init(name: String? = nil, lat: Double? = nil, lon: Double? = nil) {
        self.name = name; self.lat = lat; self.lon = lon
    }
}

public struct MapTripRef: Codable, Equatable, Sendable {
    public let tripId: String?          // identifies this exact train run
    public let routeShortName: String?  // e.g. "S1", "U6", "RE5 (79404)", "ICE 1605"

    public init(tripId: String? = nil, routeShortName: String? = nil) {
        self.tripId = tripId; self.routeShortName = routeShortName
    }
}

public struct MapTripSegment: Codable, Equatable, Sendable {
    public let trips: [MapTripRef]
    public let mode: String?            // METRO | SUBWAY | REGIONAL_RAIL | HIGHSPEED_RAIL | ...
    public let from: MapPlace?
    public let to: MapPlace?
    public let departure: String?       // ISO-8601 UTC (real-time adjusted)
    public let arrival: String?
    // Planned times — present even when realTime is false. The gap vs departure/
    // arrival is the live delay (Phase 6 / Option 1).
    public let scheduledDeparture: String?
    public let scheduledArrival: String?
    // true when MOTIS has a live feed for this trip; false = pure timetable (the
    // position is where the train *should* be, not a real-time-backed estimate).
    public let realTime: Bool
    public let polyline: String?        // Google encoded polyline, precision 5
    public let routeColor: String?      // official line colour, 6-digit hex (no '#')

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        trips = (try? c.decode([MapTripRef].self, forKey: .trips)) ?? []
        mode = try? c.decode(String.self, forKey: .mode)
        from = try? c.decode(MapPlace.self, forKey: .from)
        to = try? c.decode(MapPlace.self, forKey: .to)
        departure = try? c.decode(String.self, forKey: .departure)
        arrival = try? c.decode(String.self, forKey: .arrival)
        scheduledDeparture = try? c.decode(String.self, forKey: .scheduledDeparture)
        scheduledArrival = try? c.decode(String.self, forKey: .scheduledArrival)
        realTime = (try? c.decode(Bool.self, forKey: .realTime)) ?? false
        polyline = try? c.decode(String.self, forKey: .polyline)
        routeColor = try? c.decode(String.self, forKey: .routeColor)
    }

    public init(trips: [MapTripRef] = [], mode: String? = nil, from: MapPlace? = nil,
                to: MapPlace? = nil, departure: String? = nil, arrival: String? = nil,
                scheduledDeparture: String? = nil, scheduledArrival: String? = nil,
                realTime: Bool = false,
                polyline: String? = nil, routeColor: String? = nil) {
        self.trips = trips; self.mode = mode; self.from = from; self.to = to
        self.departure = departure; self.arrival = arrival
        self.scheduledDeparture = scheduledDeparture; self.scheduledArrival = scheduledArrival
        self.realTime = realTime
        self.polyline = polyline; self.routeColor = routeColor
    }
}

public struct TripStop: Codable, Equatable, Sendable {
    public let name: String?
    public let departure: String?
    public let arrival: String?
    public let scheduledDeparture: String?
    public let scheduledArrival: String?
}

public struct LegGeometry: Codable, Equatable, Sendable {
    public let points: String?      // encoded polyline
    public let precision: Int       // decimal digits (MOTIS trip geometry is 7)

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        points = try? c.decode(String.self, forKey: .points)
        precision = (try? c.decode(Int.self, forKey: .precision)) ?? 6
    }
}

public struct TripLeg: Codable, Equatable, Sendable {
    public let from: TripStop?
    public let to: TripStop?
    public let intermediateStops: [TripStop]
    public let headsign: String?
    public let routeShortName: String?
    public let tripId: String?
    public let mode: String?
    public let legGeometry: LegGeometry?   // the full track shape of this leg

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = try? c.decode(TripStop.self, forKey: .from)
        to = try? c.decode(TripStop.self, forKey: .to)
        intermediateStops = (try? c.decode([TripStop].self, forKey: .intermediateStops)) ?? []
        headsign = try? c.decode(String.self, forKey: .headsign)
        routeShortName = try? c.decode(String.self, forKey: .routeShortName)
        tripId = try? c.decode(String.self, forKey: .tripId)
        mode = try? c.decode(String.self, forKey: .mode)
        legGeometry = try? c.decode(LegGeometry.self, forKey: .legGeometry)
    }
}

public struct TripResponse: Codable, Equatable, Sendable {
    public let legs: [TripLeg]

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        legs = (try? c.decode([TripLeg].self, forKey: .legs)) ?? []
    }
}
