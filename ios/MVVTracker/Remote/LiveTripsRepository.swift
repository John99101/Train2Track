import Foundation
import MVVTrackerCore

/// Where live vehicles come from, shared by the Feed and the Map so switching tabs
/// reuses the last snapshot instead of downloading it again. Port of Android's
/// `LiveTripsRepository`:
///  - a city goes through the Train2Track server first (one compact snapshot for
///    everyone); geometries come separately and are kept in `ShapeStore`;
///  - only the `LiveGroup`s on screen are loaded, each with the ETag of the data
///    the app has: as long as nothing changed the server answers 304 and the app
///    keeps what it has. A server not updated yet answers 404 to these paths; then
///    the whole city is loaded as before;
///  - if the server is unreachable the app asks transitous directly, but gently:
///    at most once every two minutes per city (`FallbackPacer`), so thousands of
///    phones do not hit the community server every 20 s when ours is down. Such a
///    snapshot is flagged `delayed`;
///  - a map view outside every supported city always asks transitous directly: a
///    somewhat larger area than the screen, reused for a minute while the map
///    stays inside it.
actor LiveTripsRepository {
    static let shared = LiveTripsRepository()

    typealias Area = LivePositions.Box

    struct Snapshot: Sendable {
        let segments: [LiveSegment]
        let fetchedAtMs: Int64
        /// The server could not reach transitous and sent its last snapshot.
        let stale: Bool
        /// Our server did not answer: asked transitous directly, refreshed only every two minutes.
        var viaFallback = false
        /// A free map box: the area this snapshot covers (nil for a city).
        var area: Area? = nil

        /// Worth telling the user: the positions may lag behind.
        var delayed: Bool { stale || viaFallback }
    }

    /// The fallback or the map area may not be requested again yet.
    struct NotYet: Error {}

    private let live = LiveApiClient()
    private let transitous: TransportApi = TransportApiClient()
    private let shapes = ShapeStore.shared

    /// One group of a city as the server last sent it, and when it was last asked.
    private struct GroupData {
        let segments: [LiveSegment]
        let etag: String?
        let stale: Bool
        let checkedAtMs: Int64
    }

    /// A whole city: from a server not updated yet, or from transitous directly.
    private var cityCache: [String: Snapshot] = [:]
    private var groupCache: [String: GroupData] = [:]   // key "city|group"
    /// The server answered 404 to the group paths: load whole cities until then.
    private var groupPathsRetryAtMs: Int64 = 0
    private var inFlight: [String: Task<Snapshot, Error>] = [:]
    private var lastShapesFetch: [String: Int64] = [:]
    private var pacer = FallbackPacer()

    private var boxCache: Snapshot?
    private var lastBoxFetchMs: Int64 = 0

    /// A direct (fallback) snapshot may be shown this long (its window reaches 3 minutes ahead).
    private static let fallbackUsableMs: Int64 = 170_000
    /// Outside the cities: the same area is refreshed once a minute.
    static let boxRefreshMs: Int64 = 60_000
    /// Outside the cities: a new area (the map moved away) is fetched at most this often.
    static let boxMinGapMs: Int64 = 15_000
    private static let boxMargin = 0.15
    private static let boxMinHalfLat = 0.15
    private static let boxMinHalfLon = 0.22
    /// After a 404 to the group paths, they are tried again this much later.
    private static let groupPathsRetryMs: Int64 = 10 * 60_000
    private static let shapesMinIntervalMs: Int64 = 10 * 60_000
    private static let shapesRetryMs: Int64 = 60_000

    /// The city's vehicles of `groups`, from cache when younger than `maxAgeMs`.
    func cityTrips(_ city: GermanCity, groups: Set<LiveGroup>, maxAgeMs: Int64) async throws -> Snapshot {
        if groups.isEmpty { return Snapshot(segments: [], fetchedAtMs: nowMs(), stale: false) }
        if let s = fresh(city.id, groups: groups, maxAgeMs: maxAgeMs) { return s }
        let key = city.id + "|" + LiveGroup.allCases.filter(groups.contains).map(\.rawValue).joined(separator: ",")
        if let running = inFlight[key] { return try await running.value }
        let task = Task { () throws -> Snapshot in
            self.shapes.loadCity(city.id)
            if let s = await self.viaServer(city, groups: groups, maxAgeMs: maxAgeMs) { return s }
            return try await self.fallback(city)
        }
        inFlight[key] = task
        defer { inFlight[key] = nil }
        let snap = try await task.value
        if snap.viaFallback { cityCache[city.id] = snap }
        return snap
    }

    /// A whole city snapshot (it has every group), or else all `groups` young enough.
    private func fresh(_ cityId: String, groups: Set<LiveGroup>, maxAgeMs: Int64) -> Snapshot? {
        let now = nowMs()
        if let c = cityCache[cityId], now - c.fetchedAtMs < maxAgeMs { return c }
        var parts: [LiveGroup: GroupData] = [:]
        for g in groups {
            guard let d = groupCache[Self.groupKey(cityId, g)], now - d.checkedAtMs < maxAgeMs else { return nil }
            parts[g] = d
        }
        return Self.merged(parts)
    }

    private static func groupKey(_ cityId: String, _ group: LiveGroup) -> String { "\(cityId)|\(group.rawValue)" }

    /// The groups as one snapshot, always in the same order (rail, tram, bus).
    private static func merged(_ parts: [LiveGroup: GroupData]) -> Snapshot {
        Snapshot(segments: LiveGroup.merged(parts.mapValues(\.segments)),
                 fetchedAtMs: parts.values.map(\.checkedAtMs).min() ?? 0,
                 stale: parts.values.contains { $0.stale })
    }

    /// Vehicles for a free map view (outside the supported cities): always direct.
    /// Fetches a larger area than `view` and reuses it for `boxRefreshMs` while the
    /// view stays inside; after the map moved out of it, a new area is fetched no
    /// sooner than `boxMinGapMs` after the last one (the old one is returned
    /// meanwhile: check `Snapshot.area`).
    func boxTrips(_ view: Area) async throws -> Snapshot {
        let now = nowMs()
        if let cached = boxCache {
            let covers = cached.area?.contains(view) == true
            if covers && now - cached.fetchedAtMs < Self.boxRefreshMs { return cached }
            if now - lastBoxFetchMs < Self.boxMinGapMs { return cached }
        } else if now - lastBoxFetchMs < Self.boxMinGapMs {
            throw NotYet()
        }
        lastBoxFetchMs = now
        let wide = view.grown(factor: Self.boxMargin, minHalfLat: Self.boxMinHalfLat, minHalfLon: Self.boxMinHalfLon)
        var snap: Snapshot
        do {
            snap = try await direct(north: wide.north, south: wide.south, east: wide.east, west: wide.west, cityId: nil)
            snap.area = wide
        } catch let error as HTTPStatusError where error.status == 422 {
            // "Too many trips" for the larger area: just what is on screen.
            snap = try await direct(north: view.north, south: view.south, east: view.east, west: view.west, cityId: nil)
            snap.area = view
        }
        boxCache = snap
        return snap
    }

    /// nil when the server may not be asked right now, or did not answer properly.
    private func viaServer(_ city: GermanCity, groups: Set<LiveGroup>, maxAgeMs: Int64) async -> Snapshot? {
        if !pacer.serverAllowed(nowMs: nowMs()) { return nil }
        do {
            if nowMs() >= groupPathsRetryAtMs,
               let snap = try await byGroups(city, groups: groups, maxAgeMs: maxAgeMs) {
                cityCache[city.id] = nil
                return snap
            }
            // A server not updated yet: the whole city in one snapshot.
            let snap = try await live.live(city.id)
            requestShapesIfMissing(city.id, snap.segments)
            let whole = Snapshot(segments: snap.segments, fetchedAtMs: nowMs(), stale: snap.stale)
            cityCache[city.id] = whole
            return whole
        } catch {
            pacer.onServerFailure(nowMs: nowMs(), deviceOffline: Self.isNoConnection(error))
            return nil
        }
    }

    /// Asks the server for every group in `groups` that is older than `maxAgeMs`, at
    /// the same time. nil when the server does not know the group paths yet.
    private func byGroups(_ city: GermanCity, groups: Set<LiveGroup>, maxAgeMs: Int64) async throws -> Snapshot? {
        let now = nowMs()
        let due = LiveGroup.allCases.filter { g in
            guard groups.contains(g) else { return false }
            guard let d = groupCache[Self.groupKey(city.id, g)] else { return true }
            return now - d.checkedAtMs >= maxAgeMs
        }
        let etags: [LiveGroup: String] = due.reduce(into: [:]) { out, g in
            out[g] = groupCache[Self.groupKey(city.id, g)]?.etag
        }
        let api = live
        let cityId = city.id
        let answers = try await withThrowingTaskGroup(of: (LiveGroup, LiveApiClient.GroupAnswer).self,
                                                      returning: [(LiveGroup, LiveApiClient.GroupAnswer)].self) { tasks in
            for g in due {
                let etag = etags[g]
                tasks.addTask {
                    let answer = try await api.liveGroup(cityId, group: g, etag: etag)
                    return (g, answer)
                }
            }
            var out: [(LiveGroup, LiveApiClient.GroupAnswer)] = []
            for try await answer in tasks { out.append(answer) }
            return out
        }
        let checked = nowMs()
        for (g, answer) in answers {
            let key = Self.groupKey(city.id, g)
            switch answer {
            case .unknownPath:
                groupPathsRetryAtMs = checked + Self.groupPathsRetryMs
                return nil
            case .notModified:
                // Nothing new: keep the vehicles, they keep moving along their tracks.
                guard let old = groupCache[key] else { throw URLError(.badServerResponse) }
                groupCache[key] = GroupData(segments: old.segments, etag: old.etag, stale: old.stale, checkedAtMs: checked)
            case .data(let snap, let etag):
                requestShapesIfMissing(city.id, snap.segments)
                groupCache[key] = GroupData(segments: snap.segments, etag: etag, stale: snap.stale, checkedAtMs: checked)
            }
        }
        var parts: [LiveGroup: GroupData] = [:]
        for g in groups { parts[g] = groupCache[Self.groupKey(city.id, g)] }
        return Self.merged(parts)
    }

    private func requestShapesIfMissing(_ cityId: String, _ segments: [LiveSegment]) {
        if segments.contains(where: { !$0.shapeKey.isEmpty && !shapes.contains($0.shapeKey) }) {
            requestShapes(cityId)
        }
    }

    /// transitous directly, at most once every two minutes per city (see
    /// `FallbackPacer`). In between the last direct snapshot keeps being shown.
    private func fallback(_ city: GermanCity) async throws -> Snapshot {
        let now = nowMs()
        if !pacer.directAllowed(city.id, nowMs: now) {
            if let cached = cityCache[city.id], cached.viaFallback, now - cached.fetchedAtMs < Self.fallbackUsableMs {
                return cached
            }
            throw NotYet()
        }
        pacer.onDirectRequest(city.id, nowMs: now)
        var snap = try await direct(north: city.north, south: city.south,
                                    east: city.east, west: city.west, cityId: city.id)
        snap.viaFallback = true
        return snap
    }

    /// No route to the host at all (offline, no DNS): the phone's problem, not the
    /// server's. A timeout or an error answer counts as the server's problem.
    private static func isNoConnection(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost,
             .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
            return true
        default:
            return false
        }
    }

    /// Downloads the city's geometries in the background (at most every 10 minutes).
    /// Until they arrive, vehicles on unknown hops move on a straight line.
    private func requestShapes(_ cityId: String) {
        let now = nowMs()
        if now - (lastShapesFetch[cityId] ?? 0) < Self.shapesMinIntervalMs { return }
        lastShapesFetch[cityId] = now
        Task.detached(priority: .utility) { [live, shapes] in
            do {
                shapes.addAll(cityId, try await live.shapes(cityId))
            } catch {
                await self.retryShapesSoon(cityId, from: now)
            }
        }
    }

    private func retryShapesSoon(_ cityId: String, from: Int64) {
        lastShapesFetch[cityId] = from - Self.shapesMinIntervalMs + Self.shapesRetryMs
    }

    private func direct(north: Double, south: Double, east: Double, west: Double, cityId: String?) async throws -> Snapshot {
        let now = Date()
        let raw = try await transitous.mapTrips(
            min: "\(south),\(west)",
            max: "\(north),\(east)",
            zoom: 14,
            startTime: TransportTime.iso(now.addingTimeInterval(-Double(LiveMapConfig.windowBeforeMillis) / 1000)),
            endTime: TransportTime.iso(now.addingTimeInterval(Double(LiveMapConfig.windowAfterMillis) / 1000))
        )
        var geometries: [String: String] = [:]
        let segments = raw.compactMap { $0.toLiveSegment(shapesOut: &geometries) }
        shapes.addAll(cityId, geometries.filter { !shapes.contains($0.key) })
        return Snapshot(segments: segments, fetchedAtMs: Int64(now.timeIntervalSince1970 * 1000), stale: false)
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
