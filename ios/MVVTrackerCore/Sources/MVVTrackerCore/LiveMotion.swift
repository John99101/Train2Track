import Foundation

/// How a vehicle moves between its stops on the map. The live data has no GPS
/// positions: only each hop's departure and arrival (whole minutes, with the live
/// delay), and the apps place the vehicle along the hop's track by time.
///
///  - Standing: between arriving at a stop and leaving it again a vehicle has no
///    hop. It used to vanish from the map and the Feed for up to two minutes at
///    every stop. `withStandingTimes` gives every hop the moment from which its
///    vehicle stands at the stop it leaves from (`LiveSegment.standFromMs`).
///  - Speed: a vehicle speeds up after a stop and brakes before the next one, it
///    does not run at one speed from stop to stop. `distanceFraction` turns the share
///    of a hop's time into the share of its way with a slow start, a steady middle
///    and a slow arrival.
///
/// Port of Android's `LiveMotion.kt`.
public enum LiveMotion {
    /// At most this long standing at a stop (a long turn at the end of a line is cut).
    public static let maxStandMs: Int64 = 5 * 60_000

    /// A vehicle whose earlier hop is not in the data (it starts its journey there, or
    /// it arrived before the data's time window) is shown at its stop this long before
    /// it leaves.
    public static let firstStandMs: Int64 = 60_000

    /// The hops with `standFromMs`: from the arrival of the vehicle's hop before (at the
    /// same stop), else `firstStandMs` before the departure. Hops without a journey id
    /// keep their departure (no way to know their vehicle's hop before, and two badges
    /// for one vehicle would be worse than a gap).
    /// When a vehicle started standing at a stop, kept across downloads. The data only
    /// reaches about a minute back, so at a long stop the hop that brought the vehicle
    /// drops out while it still stands there; without this its standing time would
    /// jump to `firstStandMs` before it leaves and it would vanish from the map.
    /// Keyed by journey and stop; a time is only taken over while the departure moved
    /// less than `maxStandMs` (a delay, not a second visit of a loop line).
    /// Port of Android's `LiveMotion.StandMemory`.
    public final class StandMemory: @unchecked Sendable {
        private struct Seen {
            let standFromMs: Int64
            let departureMs: Int64
        }

        private let lock = NSLock()
        private var seen: [String: Seen] = [:]

        public init() {}

        public func keep(_ key: String, standFromMs: Int64, departureMs: Int64) -> Int64 {
            lock.lock()
            defer { lock.unlock() }
            var kept = standFromMs
            if let earlier = seen[key], earlier.standFromMs < standFromMs,
               abs(earlier.departureMs - departureMs) <= LiveMotion.maxStandMs {
                kept = max(earlier.standFromMs, departureMs - LiveMotion.maxStandMs)
            }
            seen[key] = Seen(standFromMs: kept, departureMs: departureMs)
            return kept
        }

        /// Forgets vehicles that left long ago.
        public func prune(nowMs: Int64) {
            lock.lock()
            defer { lock.unlock() }
            seen = seen.filter { $0.value.departureMs >= nowMs - LiveMotion.maxStandMs }
        }
    }

    public static func withStandingTimes(_ segments: [LiveSegment], memory: StandMemory? = nil,
                                         nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [LiveSegment] {
        var stand = segments.map { $0.tripId == nil ? $0.departureMs : $0.departureMs - firstStandMs }
        var byTrip: [String: [Int]] = [:]
        for (i, s) in segments.enumerated() {
            if let trip = s.tripId { byTrip[trip, default: []].append(i) }
        }
        for var indices in byTrip.values where indices.count >= 2 {
            indices.sort { segments[$0].departureMs < segments[$1].departureMs }
            for k in 1..<indices.count {
                let prev = segments[indices[k - 1]]
                let i = indices[k]
                let s = segments[i]
                stand[i] = prev.arrivalMs <= s.departureMs && sameStop(prev, s)
                    ? max(prev.arrivalMs, s.departureMs - maxStandMs)
                    : s.departureMs   // the hops overlap (the same hop twice) or do not meet
            }
        }
        if let memory {
            memory.prune(nowMs: nowMs)
            for (i, s) in segments.enumerated() {
                guard let trip = s.tripId, stand[i] < s.departureMs else { continue }
                stand[i] = memory.keep("\(trip)|\(stopKey(s))", standFromMs: stand[i], departureMs: s.departureMs)
            }
        }
        return segments.enumerated().map { i, s in
            var out = s
            out.standFromMs = stand[i]
            return out
        }
    }

    /// The stop a hop leaves from: its name, else its place.
    private static func stopKey(_ s: LiveSegment) -> String {
        s.fromName ?? "\(s.fromLat),\(s.fromLon)"
    }

    /// Whether hop `b` starts where hop `a` ends.
    private static func sameStop(_ a: LiveSegment, _ b: LiveSegment) -> Bool {
        if let to = a.toName, to == b.fromName { return true }
        if a.toLat.isNaN || b.fromLat.isNaN { return false }
        return abs(a.toLat - b.fromLat) < 0.002 && abs(a.toLon - b.fromLon) < 0.003
    }

    /// Seconds a vehicle of `mode` takes to reach its speed after a stop (and to brake before one).
    private static func rampSeconds(_ mode: String) -> Double {
        switch mode {
        case "BUS": return 12
        case "TRAM": return 15
        case "SUBWAY": return 20
        default: return 25
        }
    }

    /// The share of its way a vehicle of `mode` has covered `elapsedMs` into a hop of
    /// `durationMs`: speeding up, steady, braking (the speeding up and the braking take
    /// at most a third of the hop each). 0 before it leaves, 1 when it arrives.
    public static func distanceFraction(elapsedMs: Int64, durationMs: Int64, mode: String) -> Double {
        if durationMs <= 0 { return 1 }
        let total = Double(durationMs) / 1000
        let t = min(max(Double(elapsedMs) / 1000, 0), total)
        let ramp = min(rampSeconds(mode), total / 3)
        let speed = 1 / (total - ramp)   // the steady speed, in shares of the way per second
        if t < ramp { return speed * t * t / (2 * ramp) }
        if t > total - ramp { return 1 - speed * (total - t) * (total - t) / (2 * ramp) }
        return speed * (t - ramp / 2)
    }
}
