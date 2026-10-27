import Foundation
import MVVTrackerCore

/// The Stations tab's search: it waits until the rider stops typing for a moment,
/// so not every letter sends a request. Port of Android's `StationsViewModel`.
@MainActor
final class StationsViewModel: ObservableObject {
    enum SearchState: Equatable {
        /// Nothing typed yet (or too little): the recently opened stations are shown.
        case idle
        case loading
        case results([StationRef])
        case error
    }

    @Published var text = "" { didSet { schedule() } }
    @Published private(set) var state: SearchState = .idle

    var cityId = germanCities[0].id {
        didSet { if oldValue != cityId { schedule() } }
    }

    private let client = StationApiClient()
    private var task: Task<Void, Never>?

    private func schedule() {
        task?.cancel()
        guard StationParser.normalizeQuery(text) != nil else {
            state = .idle
            return
        }
        let city = cityId
        let query = text
        task = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled, let self else { return }
            self.state = .loading
            do {
                let list = try await self.client.search(cityId: city, text: query)
                if !Task.isCancelled { self.state = .results(list) }
            } catch {
                if !Task.isCancelled { self.state = .error }
            }
        }
    }
}

/// One station's departures: loaded when the screen appears and again
/// every minute while it is on screen (the server keeps them 30 seconds for
/// everybody); nothing runs once the rider leaves it. Port of Android's
/// `StationBoardViewModel`.
@MainActor
final class StationBoardViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case loaded(StationBoard)
        case error
    }

    @Published private(set) var state: State = .loading

    private let client = StationApiClient()
    private var task: Task<Void, Never>?

    func start(_ station: StationRef) {
        StationRecents.shared.opened(station)
        task?.cancel()
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let ok = await self.load(station)
                // Failed (no connection): try again a little later, not at once.
                try? await Task.sleep(nanoseconds: (ok ? 60 : 30) * 1_000_000_000)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    func retry(_ station: StationRef) {
        Task { _ = await load(station) }
    }

    /// true when the board came; on failure the last board stays on screen.
    private func load(_ station: StationRef) async -> Bool {
        do {
            state = .loaded(try await client.board(station))
            return true
        } catch {
            if case .loaded = state {} else { state = .error }
            return false
        }
    }
}
