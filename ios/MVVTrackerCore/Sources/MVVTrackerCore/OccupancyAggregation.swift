import Foundation

/// One rider's answer to "how full is it?" on a journey, as read from Firestore.
/// Port of Android's `OccupancyVote`.
public struct OccupancyVote: Equatable, Sendable {
    public let tripId: String
    /// 1 = many seats free ... 5 = very full.
    public let level: Int
    public let expiresAtMs: Int64?
    /// Over the spam limit (set by the server): does not count.
    public let throttled: Bool
    /// Written by this installation.
    public let own: Bool

    public init(tripId: String, level: Int, expiresAtMs: Int64?, throttled: Bool, own: Bool) {
        self.tripId = tripId
        self.level = level
        self.expiresAtMs = expiresAtMs
        self.throttled = throttled
        self.own = own
    }
}

/// What the app shows for one journey: `level` is the average of the current votes,
/// rounded to a level (nil without votes). `ownLevel` is this installation's
/// current answer, if any. Port of Android's `TripOccupancy`.
public struct TripOccupancy: Equatable, Sendable {
    public let level: Int?
    public let votes: Int
    public let ownLevel: Int?

    public init(level: Int?, votes: Int, ownLevel: Int?) {
        self.level = level
        self.votes = votes
        self.ownLevel = ownLevel
    }
}

/// Turns the occupancy votes into one level per journey: the average of the
/// current votes, rounded to the nearest level (halfway rounds up). So 3 and 4 show
/// 4, and a third rider with 3 brings it back to 3: every vote moves it. A vote
/// counts for 20 minutes (its expiry), because a train fills and empties at every
/// stop; votes over the spam limit never count. Port of Android's `OccupancyAggregation`.
public enum OccupancyAggregation {
    public static let levels = 5
    public static let minVotes = 1
    /// How long a vote counts (the expiry the apps write).
    public static let ttl: TimeInterval = 20 * 60

    public static func aggregate(_ votes: [OccupancyVote], nowMs: Int64) -> [String: TripOccupancy] {
        let current = votes.filter {
            !$0.throttled && (1...levels).contains($0.level) && ($0.expiresAtMs ?? 0) > nowMs
        }
        return Dictionary(grouping: current, by: \.tripId).mapValues { trip in
            TripOccupancy(
                level: trip.count >= minVotes ? average(trip.map(\.level)) : nil,
                votes: trip.count,
                ownLevel: trip.first(where: \.own)?.level
            )
        }
    }

    /// The average level, rounded to the nearest level; exactly halfway rounds up.
    /// `levels` must not be empty.
    public static func average(_ levels: [Int]) -> Int {
        precondition(!levels.isEmpty)
        let n = levels.count
        return (2 * levels.reduce(0, +) + n) / (2 * n)
    }
}
