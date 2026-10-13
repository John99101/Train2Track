import Foundation
import MVVTrackerCore

/// Track geometries by key, kept for good: each hop between two stops is decoded
/// once and reused on every position tick, and saved per city (Application Support)
/// so the geometries are simply there on the next app start instead of being
/// downloaded again. Port of Android's `ShapeStore`. Thread safe: positions are
/// computed off the main thread.
final class ShapeStore: @unchecked Sendable {
    static let shared = ShapeStore()

    private let lock = NSLock()
    private let dir = LiveCacheDir.url("live_shapes")
    private let ioQueue = DispatchQueue(label: "train2track.shapes.io", qos: .utility)

    /// Every known geometry, key -> encoded polyline.
    private var encoded: [String: String] = [:]
    /// Keys saved in each city's file.
    private var cityKeys: [String: Set<String>] = [:]
    private var loaded: Set<String> = []
    /// Decoded tracks; dropped wholesale when the cap is reached (cheap to rebuild).
    private var tracks: [String: Track] = [:]

    private static let maxTracks = 15_000
    private static let maxPerCity = 30_000

    func contains(_ key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return encoded[key] != nil
    }

    /// The decoded track for `key`, decoding it on first use; nil when unknown.
    func track(_ key: String) -> Track? {
        lock.lock()
        if let t = tracks[key] { lock.unlock(); return t }
        guard let poly = encoded[key] else { lock.unlock(); return nil }
        lock.unlock()
        guard let track = Track.of(GeoPolyline.decode(poly)) else { return nil }
        lock.lock()
        if tracks.count >= Self.maxTracks { tracks.removeAll(keepingCapacity: true) }
        tracks[key] = track
        lock.unlock()
        return track
    }

    /// Loads a city's saved geometries (once per app run).
    func loadCity(_ cityId: String) {
        lock.lock()
        let first = loaded.insert(cityId).inserted
        lock.unlock()
        guard first else { return }
        let file = fileFor(cityId)
        guard let data = try? Data(contentsOf: file) else { return }
        guard let saved = try? JSONDecoder().decode([String: String].self, from: data) else {
            try? FileManager.default.removeItem(at: file)   // corrupt cache: start over
            return
        }
        lock.lock()
        encoded.merge(saved) { _, new in new }
        cityKeys[cityId, default: []].formUnion(saved.keys)
        lock.unlock()
    }

    /// Adds geometries; with a `cityId` they are also saved in that city's file.
    func addAll(_ cityId: String?, _ shapes: [String: String]) {
        if shapes.isEmpty { return }
        lock.lock()
        encoded.merge(shapes) { _, new in new }
        guard let cityId else { lock.unlock(); return }
        var keys = cityKeys[cityId, default: []]
        if keys.isSuperset(of: shapes.keys) { lock.unlock(); return }
        keys.formUnion(shapes.keys)
        // A city collects new hops over time; past a cap start again from the latest set.
        if keys.count > Self.maxPerCity { keys = Set(shapes.keys) }
        cityKeys[cityId] = keys
        var snapshot: [String: String] = [:]
        for k in keys { if let p = encoded[k] { snapshot[k] = p } }
        lock.unlock()
        let file = fileFor(cityId)
        ioQueue.async {
            guard let data = try? JSONEncoder().encode(snapshot) else { return }
            try? data.write(to: file, options: .atomic)
        }
    }

    private func fileFor(_ cityId: String) -> URL { dir.appendingPathComponent("\(cityId).json") }
}
