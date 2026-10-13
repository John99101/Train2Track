import Foundation
import Combine
import MVVTrackerCore

/// Streams live vehicles for a whole city (the Feed, or a map view inside a city)
/// or for a free map box (a map view outside the supported cities). Port of
/// Android's `LiveMapViewModel`. Two loops run:
///  - a NETWORK loop that refreshes the vehicles via `LiveTripsRepository` (shared
///    with the other tab, so switching tabs does not download again);
///  - a TICK loop that places every vehicle along its pre-decoded track
///    (`ShapeStore`) off the main thread; the map then glides the markers.
/// Both idle while the screen is hidden (`onAppear`/`onDisappear`).
@MainActor
final class LiveMapViewModel: ObservableObject {

    /// What is being shown: a whole city, or a free map box outside the cities.
    private enum Source: Equatable {
        case city(GermanCity)
        case box(LivePositions.Box)
    }

    @Published private(set) var trains: [TrainPosition] = []
    @Published private(set) var loadError = false
    /// The positions may lag: our server is unreachable or serves an older snapshot.
    @Published private(set) var dataDelayed = false
    /// The report cities of what is on screen (see `reportScope`): the screen hands
    /// them to its `ReportCountsViewModel`, which loads only these reports.
    @Published private(set) var reportCities: [String] = []
    /// Full line routes, keyed by `routeKey(mode:label:)`.
    @Published private(set) var routes: [String: [GeoPoint]] = [:]

    private let repo = LiveTripsRepository.shared
    private let shapes = ShapeStore.shared
    private let transitous: TransportApi = TransportApiClient()

    private var source: Source?
    /// The map's visible area; markers outside it (plus a margin) are not built.
    private var viewport: LivePositions.Box?
    /// The Feed only lists vehicles, it needs no positions (and fewer ticks).
    private var positionsWanted = true
    private var segments: [LiveSegment] = []
    private var active = false

    private var fetchTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var computeTask: Task<Void, Never>?

    // Line routes for the current city (or free map area).
    private var routeByKey: [String: [GeoPoint]] = [:]
    private var routeInFlight: Set<String> = []
    private var routeScope: String?
    private var runningRouteFetches = 0
    private var routeQueue: [(key: String, seg: LiveSegment, scope: String?)] = []

    func onAppear() {
        active = true
        startTickLoop()
        restartFetchLoop()
    }

    func onDisappear() {
        active = false
        fetchTask?.cancel(); fetchTask = nil
        tickTask?.cancel(); tickTask = nil
    }

    /// Map: the visible area changed. Inside a city the whole city is used.
    func updateBounds(north: Double, south: Double, east: Double, west: Double) {
        positionsWanted = true
        let b = LivePositions.Box(north: north, south: south, east: east, west: west)
        viewport = b
        let next: Source?
        if let city = cityForBox(north: north, south: south, east: east, west: west) {
            next = .city(city)
        } else if b.isReasonable {
            next = .box(b)
        } else {
            next = source   // zoomed out too far: keep what is shown
        }
        setSource(next)
        recomputeSoon()
    }

    /// Feed: list a whole city (no positions needed).
    func showCity(_ city: GermanCity) {
        positionsWanted = false
        viewport = nil
        setSource(.city(city))
    }

    private func setSource(_ next: Source?) {
        guard let next else { return }
        // A free box that moved a bit inside the same area is still the same source
        // for routes; the fetch follows the new box.
        if next != source {
            source = next
            let cities = Self.scopeCities(of: next)
            if cities != reportCities { reportCities = cities }
            if positionsWanted { prepareRoutes(next) }
            if active { restartFetchLoop() }
        }
    }

    private static func scopeCities(of src: Source) -> [String] {
        switch src {
        case .city(let city):
            return reportScope(for: city)
        case .box(let b):
            return reportScope(north: b.north, south: b.south, east: b.east, west: b.west)
        }
    }

    /// The city a report on `train` is filed under: the city being shown, or on a
    /// free map box the city the train's position lies in ("other" outside them all).
    func reportCity(for train: TrainPosition) -> String {
        if case .city(let city) = source { return city.id }
        return MVVTrackerCore.reportCity(latitude: train.latitude, longitude: train.longitude)
    }

    // MARK: - Loops

    private func restartFetchLoop() {
        fetchTask?.cancel()
        guard let src = source else { return }
        fetchTask = Task { [weak self] in
            // A free map box goes to transitous directly: wait until the map has
            // stopped moving instead of asking once per pan.
            if case .box = src {
                try? await Task.sleep(nanoseconds: UInt64(LiveMapConfig.boxSettleMillis) * 1_000_000)
            }
            while !Task.isCancelled {
                let waitMs = await self?.fetchOnce(src) ?? LiveMapConfig.fetchCityMillis
                try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
            }
        }
    }

    /// Loads the vehicles once and returns how long to wait before the next load:
    /// the map refreshes a city every 20 s, the Feed (a list, no positions) every
    /// 30 s; a free map box once a minute (`LiveTripsRepository` paces it).
    private func fetchOnce(_ src: Source) async -> Int {
        let cityMs = positionsWanted ? LiveMapConfig.fetchCityMillis : LiveMapConfig.fetchFeedMillis
        guard active else { return cityMs }
        do {
            let snap: LiveTripsRepository.Snapshot
            var waitMs = cityMs
            switch src {
            case .city(let city):
                snap = try await repo.cityTrips(city, maxAgeMs: Int64(cityMs - 2_000))
            case .box(let b):
                snap = try await repo.boxTrips(b)
                // The map moved out of the fetched area too soon: ask again shortly.
                let covers = snap.area?.contains(b) == true
                waitMs = Int(covers ? LiveTripsRepository.boxRefreshMs : LiveTripsRepository.boxMinGapMs)
            }
            guard !Task.isCancelled, source == src else { return waitMs }
            segments = snap.segments
            loadError = false
            dataDelayed = snap.delayed
            recomputeSoon()
            return waitMs
        } catch {
            if !Task.isCancelled { loadError = true }   // keep last positions
            if case .box = src { return Int(LiveTripsRepository.boxMinGapMs) }
            return cityMs
        }
    }

    private func startTickLoop() {
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.recomputeSoon()
                let ms = (self?.positionsWanted ?? true) ? LiveMapConfig.tickMillis : LiveMapConfig.listTickMillis
                try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
            }
        }
    }

    /// Places the vehicles on a background thread and publishes the result.
    private func recomputeSoon() {
        guard active else { return }
        let segs = segments
        let withPositions = positionsWanted
        let area = withPositions ? viewport?.expanded(LiveMapConfig.viewportMargin) : nil
        let store = shapes
        computeTask?.cancel()
        computeTask = Task { [weak self] in
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let result = await Task.detached(priority: .userInitiated) {
                LivePositions.compute(segs, nowMs: now, withPositions: withPositions, area: area) { store.track($0) }
            }.value
            guard !Task.isCancelled, let self, self.active else { return }
            self.trains = result
        }
    }

    // MARK: - Line routes

    /// Switches the route set when the city (or the free map area) changes.
    private func prepareRoutes(_ src: Source) {
        let scope: String
        switch src {
        case .city(let city):
            scope = city.id
        case .box(let b):
            let lat = (b.north + b.south) / 2, lon = (b.east + b.west) / 2
            if let prev = routeScope, prev.hasPrefix("box:") {
                let parts = prev.dropFirst(4).split(separator: ",").compactMap { Double($0) }
                // Keep the routes while panning around the same area.
                if parts.count == 2, abs(parts[0] - lat) <= 0.25, abs(parts[1] - lon) <= 0.25 { return }
            }
            scope = "box:\(lat),\(lon)"
        }
        if scope == routeScope { return }
        routeScope = scope
        routeByKey = [:]
        routeInFlight = []
        routeQueue = []
        routes = [:]
        guard case .city = src else { return }
        Task { [weak self] in
            // Saved routes first (instant), then the server set if the saved one is old.
            let saved = await RouteStore.shared.cached(scope)
            guard let self, self.routeScope == scope else { return }
            self.routeByKey.merge(saved) { _, new in new }
            self.routes = self.routeByKey
            if let fresh = await RouteStore.shared.refreshIfStale(scope), self.routeScope == scope {
                self.routeByKey.merge(fresh) { _, new in new }
                self.routes = self.routeByKey
            }
        }
    }

    /// Makes sure the routes for `keys` the Map wants to draw are there: anything
    /// missing from the route set is fetched once from the trip of a vehicle
    /// currently on that line (at most 5 at a time), and saved for the city.
    func ensureRoutes(_ keys: Set<String>) {
        let scope = routeScope
        for key in keys {
            if routeByKey[key] != nil || routeInFlight.contains(key) { continue }
            guard let seg = segments.first(where: { $0.tripId != nil && routeKey(mode: $0.mode, label: $0.label) == key }) else { continue }
            routeInFlight.insert(key)
            routeQueue.append((key, seg, scope))
        }
        pumpRouteQueue()
    }

    private func pumpRouteQueue() {
        while runningRouteFetches < 5, !routeQueue.isEmpty {
            let job = routeQueue.removeFirst()
            runningRouteFetches += 1
            Task { [weak self] in
                guard let self else { return }
                let pts = await self.fetchTripRoute(job.seg.tripId!)
                self.runningRouteFetches -= 1
                if let pts, pts.count >= 2, self.routeScope == job.scope {
                    self.routeByKey[job.key] = pts
                    self.routes = self.routeByKey
                    if let scope = job.scope, !scope.hasPrefix("box:") {
                        await RouteStore.shared.addOnDemand(scope, mode: job.seg.mode, label: job.seg.label, points: pts)
                    }
                }
                self.routeInFlight.remove(job.key)
                self.pumpRouteQueue()
            }
        }
    }

    private nonisolated func fetchTripRoute(_ tripId: String) async -> [GeoPoint]? {
        guard let resp = try? await TransportApiClient().trip(tripId: tripId) else { return nil }
        let pts = resp.legs.flatMap { leg -> [GeoPoint] in
            guard let g = leg.legGeometry, let p = g.points else { return [] }
            return GeoPolyline.decode(p, precision: g.precision)
        }
        if pts.isEmpty { return nil }
        // Thin the dense precision-7 line (~3 m tolerance) so it is cheap to draw.
        return GeoPolyline.simplify(pts, toleranceDeg: 0.00003)
    }
}
