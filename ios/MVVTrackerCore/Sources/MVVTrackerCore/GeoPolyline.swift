import Foundation
import CryptoKit

/// A decoded `[latitude, longitude]` pair. Kept dependency-free (not
/// `CLLocationCoordinate2D`) so the core stays testable on any platform.
public struct GeoPoint: Equatable, Sendable {
    public let lat: Double
    public let lon: Double
    public init(_ lat: Double, _ lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}

/// Decoding of Google "encoded polyline" strings (precision 5, as used by MOTIS)
/// and interpolation of a point a given fraction of the way along the line.
/// Used to turn a train's `[departure, arrival]` track segment into a live
/// position.
///
/// Direct port of `data/remote/GeoPolyline.kt` — do not change the algorithm,
/// it must produce byte-for-byte the same positions as Android.
public enum GeoPolyline {

    /// Short stable key for an encoded geometry: the first 12 characters of its
    /// SHA-1 in URL-safe base64 (no padding). Must match `shapeKey()` in
    /// functions/lib/transitous.js and Android, so geometries fetched directly from
    /// transitous and via the server share one cache.
    public static func shapeKey(_ encoded: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(encoded.utf8))
        let b64 = Data(digest).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return String(b64.prefix(12))
    }

    /// Encodes points as a Google encoded polyline (inverse of `decode`).
    /// Port of `GeoPolyline.encode` (rounding like Java's `Math.round`).
    public static func encode(_ points: [GeoPoint], precision: Int = 5) -> String {
        let factor = pow(10.0, Double(precision))
        var out = [UInt8]()
        var prevLat: Int64 = 0
        var prevLng: Int64 = 0
        func put(_ delta: Int64) {
            var v = delta < 0 ? ~(delta << 1) : (delta << 1)
            while v >= 0x20 {
                out.append(UInt8((0x20 | (v & 0x1f)) + 63))
                v >>= 5
            }
            out.append(UInt8(v + 63))
        }
        for p in points {
            let lat = Int64((p.lat * factor + 0.5).rounded(.down))
            let lng = Int64((p.lon * factor + 0.5).rounded(.down))
            put(lat - prevLat)
            put(lng - prevLng)
            prevLat = lat
            prevLng = lng
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Returns the polyline points as `[latitude, longitude]` pairs. `precision` is
    /// the number of decimal digits the coordinates were encoded with: MOTIS
    /// map/trips segments use 5, while /api/v1/trip leg geometry uses 7.
    ///
    /// A damaged string (cut off in the middle of a value, or not a polyline at
    /// all) gives the points decoded up to that place instead of crashing: the
    /// strings come from the network and are kept on the device, so one bad string
    /// would otherwise crash the app at every start.
    public static func decode(_ encoded: String, precision: Int = 5) -> [GeoPoint] {
        let factor = pow(10.0, Double(precision))
        let chars = Array(encoded.utf8) // polyline chars are ASCII
        var points: [GeoPoint] = []
        var index = 0
        var lat = 0
        var lng = 0

        /// The next value, or nil when the string ends before the value does.
        func nextValue() -> Int? {
            var shift = 0
            var result = 0
            var b: Int
            repeat {
                guard index < chars.count, shift < 64 else { return nil }
                b = Int(chars[index]) - 63
                index += 1
                result |= (b & 0x1f) << shift
                shift += 5
            } while b >= 0x20
            return (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
        }

        while index < chars.count {
            guard let dLat = nextValue(), let dLng = nextValue() else { break }
            lat &+= dLat
            lng &+= dLng
            points.append(GeoPoint(Double(lat) / factor, Double(lng) / factor))
        }
        return points
    }

    /// Ramer–Douglas–Peucker simplification: drops points that stray less than
    /// `toleranceDeg` (degrees) from the line, cutting a dense route to far fewer
    /// points so it is cheaper to store and draw. Iterative (no deep recursion).
    public static func simplify(_ points: [GeoPoint], toleranceDeg: Double) -> [GeoPoint] {
        let n = points.count
        if n < 3 { return points }
        var keep = [Bool](repeating: false, count: n)
        keep[0] = true
        keep[n - 1] = true
        var stack: [(Int, Int)] = [(0, n - 1)]
        while let (first, last) = stack.popLast() {
            var maxDist = 0.0
            var idx = -1
            if first + 1 < last {
                for i in (first + 1)..<last {
                    let d = perpDistanceDeg(points[i], points[first], points[last])
                    if d > maxDist { maxDist = d; idx = i }
                }
            }
            if maxDist > toleranceDeg && idx != -1 {
                keep[idx] = true
                stack.append((first, idx))
                stack.append((idx, last))
            }
        }
        var out: [GeoPoint] = []
        for i in 0..<n where keep[i] { out.append(points[i]) }
        return out
    }

    /// Perpendicular distance (in degrees, longitude latitude-corrected) from p to line a–b.
    private static func perpDistanceDeg(_ p: GeoPoint, _ a: GeoPoint, _ b: GeoPoint) -> Double {
        let kx = cos(((a.lat + b.lat) / 2.0) * .pi / 180.0)
        let ax = a.lon * kx, ay = a.lat
        let bx = b.lon * kx, by = b.lat
        let px = p.lon * kx, py = p.lat
        let dx = bx - ax, dy = by - ay
        let lenSq = dx * dx + dy * dy
        if lenSq == 0.0 {
            let ex = px - ax, ey = py - ay
            return (ex * ex + ey * ey).squareRoot()
        }
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = min(1.0, max(0.0, t))
        let projX = ax + t * dx, projY = ay + t * dy
        let ex = px - projX, ey = py - projY
        return (ex * ex + ey * ey).squareRoot()
    }

    /// The point `fraction` (0..1) of the way along `points`, measured by
    /// (latitude-corrected) planar distance. Returns `[lat, lon]`, or nil if empty.
    public static func pointAtFraction(_ points: [GeoPoint], _ fraction: Double) -> GeoPoint? {
        if points.isEmpty { return nil }
        if points.count == 1 || fraction <= 0.0 { return points.first }
        if fraction >= 1.0 { return points.last }

        var segmentLengths = [Double](repeating: 0.0, count: points.count - 1)
        var total = 0.0
        for i in 0..<(points.count - 1) {
            let a = points[i]
            let c = points[i + 1]
            let midLatRad = ((a.lat + c.lat) / 2.0) * .pi / 180.0
            let dLat = c.lat - a.lat
            let dLon = (c.lon - a.lon) * cos(midLatRad)
            let d = (dLat * dLat + dLon * dLon).squareRoot()
            segmentLengths[i] = d
            total += d
        }
        if total <= 0.0 { return points.first }

        var target = fraction * total
        for i in segmentLengths.indices {
            let len = segmentLengths[i]
            if target <= len {
                let t = len == 0.0 ? 0.0 : target / len
                let a = points[i]
                let c = points[i + 1]
                return GeoPoint(a.lat + (c.lat - a.lat) * t, a.lon + (c.lon - a.lon) * t)
            }
            target -= len
        }
        return points.last
    }
}

/// A decoded track, prepared once so positions along it are cheap: the points and
/// the (latitude-corrected) cumulative distance from the start to each point.
/// `pointAt` is then a binary search instead of re-measuring the whole line.
/// Port of Android's `Track` (`GeoPolyline.kt`).
public final class Track: @unchecked Sendable {
    public let lats: [Double]
    public let lons: [Double]
    private let cumulative: [Double]

    public var count: Int { lats.count }

    private init(lats: [Double], lons: [Double], cumulative: [Double]) {
        self.lats = lats; self.lons = lons; self.cumulative = cumulative
    }

    public static func of(_ points: [GeoPoint]) -> Track? {
        if points.isEmpty { return nil }
        let n = points.count
        var lats = [Double](repeating: 0, count: n)
        var lons = [Double](repeating: 0, count: n)
        var cum = [Double](repeating: 0, count: n)
        for i in 0..<n { lats[i] = points[i].lat; lons[i] = points[i].lon }
        if n > 1 {
            for i in 1..<n {
                let dLat = lats[i] - lats[i - 1]
                let dLon = (lons[i] - lons[i - 1]) * cos(((lats[i] + lats[i - 1]) / 2.0) * .pi / 180.0)
                cum[i] = cum[i - 1] + (dLat * dLat + dLon * dLon).squareRoot()
            }
        }
        return Track(lats: lats, lons: lons, cumulative: cum)
    }

    /// The point `fraction` (0..1) of the way along the track.
    public func pointAt(_ fraction: Double) -> GeoPoint {
        let n = lats.count
        let total = cumulative[n - 1]
        if n == 1 || fraction <= 0.0 || total <= 0.0 { return GeoPoint(lats[0], lons[0]) }
        if fraction >= 1.0 { return GeoPoint(lats[n - 1], lons[n - 1]) }
        let target = fraction * total
        var lo = 0
        var hi = n - 1
        while hi - lo > 1 {
            let mid = (lo + hi) >> 1
            if cumulative[mid] < target { lo = mid } else { hi = mid }
        }
        let len = cumulative[hi] - cumulative[lo]
        let t = len <= 0.0 ? 0.0 : (target - cumulative[lo]) / len
        return GeoPoint(lats[lo] + (lats[hi] - lats[lo]) * t, lons[lo] + (lons[hi] - lons[lo]) * t)
    }
}
